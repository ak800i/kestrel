package dev.kestrel.app.conversation

import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The buttons of Kestrel's question card (design 10.3 of sub-project 7). */
class OwnerCardTest {
    private fun card(kind: QuestionKind, reply: Boolean = true, picks: List<String> = emptyList()): OwnerCard =
        OwnerCard.of(ChannelQuestion(7, "Question?", kind, choices = picks), reply)

    @Test
    fun eachKindHasItsButtonsAndOnlyTheKindsThatTakeWordsHaveReply() {
        assertEquals(listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply), card(QuestionKind.YES_NO).choices)
        assertEquals(listOf(CardChoice.Confirm, CardChoice.Cancel), card(QuestionKind.CONFIRM).choices)
        assertEquals(listOf(CardChoice.Reply, CardChoice.Cancel), card(QuestionKind.FREE).choices)
        assertEquals(listOf(CardChoice.Yes, CardChoice.No, CardChoice.Reply), card(QuestionKind.SCREENSHOT).choices)
        // A resuming button comes last, as on the goal notification (plan 7E).
        assertEquals(listOf(CardChoice.Stop, CardChoice.Continue), card(QuestionKind.CONTINUE_GOAL).choices)
        assertEquals(listOf(CardChoice.Stop, CardChoice.Continue), card(QuestionKind.LIMIT).choices)
        assertEquals(listOf(CardChoice.Yes, CardChoice.No), card(QuestionKind.SWITCH).choices)
        assertEquals(listOf(CardChoice.Yes, CardChoice.No), card(QuestionKind.DELETE_HISTORY).choices)
        // The voice's cards have no Reply…: a confirmation's and the screenshot question's.
        assertEquals(listOf(CardChoice.Yes, CardChoice.No), card(QuestionKind.SCREENSHOT, reply = false).choices)
        // Only the overlay answers a confirmation and a pick, over the app and its numbers (design 12.2 and 10.3).
        assertFalse(card(QuestionKind.CONFIRM).answersOnScreen)
        assertFalse(card(QuestionKind.PICK, picks = listOf("1", "2")).answersOnScreen)
        assertTrue(card(QuestionKind.YES_NO).answersOnScreen)
    }

    @Test
    fun aPickShowsItsChoicesOrAKeypadAndCancelAndEachButtonGivesItsAnswer() {
        val choices = OwnerCard.of(ChannelQuestion(3, "Which number should I use?", QuestionKind.PICK, choices = listOf("1", "2", "3", "4", "5")), reply = true)

        assertEquals(listOf("1", "2", "3", "4"), choices.picks)
        assertEquals(listOf(CardChoice.Pick(1), CardChoice.Pick(2), CardChoice.Pick(3), CardChoice.Pick(4), CardChoice.Cancel), choices.choices)
        assertFalse(choices.numberPad)
        assertEquals(3, choices.key)
        assertEquals(3, choices.question)

        val numbers = OwnerCard.of(ChannelQuestion(8, "Which number should I use?", QuestionKind.PICK, highest = 20), reply = true)
        assertEquals(listOf(CardChoice.Cancel), numbers.choices)
        assertTrue(numbers.numberPad)
        assertEquals(20, numbers.highest)

        assertEquals(ChannelAnswer.Pick(4), CardChoice.Pick(4).answer)
        assertEquals(ChannelAnswer.Yes, CardChoice.Confirm.answer)
        assertEquals(ChannelAnswer.Yes, CardChoice.Continue.answer)
        assertEquals(ChannelAnswer.No, CardChoice.Stop.answer)
        assertEquals(ChannelAnswer.No, CardChoice.Cancel.answer)
        assertNull(CardChoice.Reply.answer)
        assertNull(CardChoice.Pick(1).label)
    }

    @Test
    fun aHandOversCardHasContinueAndCancelNoQuestionAndOnlyTheOverlayAndPrintsNoWords() {
        val card = OwnerCard.handOver(-2, "Sign in to Viber yourself, then tap Continue.")

        assertEquals(listOf(CardChoice.Cancel, CardChoice.Continue), card.choices)
        assertEquals(-2, card.key)
        assertNull(card.question)
        assertFalse(card.answersOnScreen)
        assertFalse(card.toString().contains("Viber"))
    }
}
