package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.model.ChannelAnswer
import org.junit.Assert.assertEquals
import org.junit.Test

/** The spoken answers that the conversation's questions take (design D2 of sub-project 5; design 11 of sub-project 7). */
class VoiceAnswersTest {
    private fun read(kind: QuestionKind, text: String, named: Boolean = false): ChannelAnswer? =
        VoiceAnswers.read(kind, text, named, Requests.read(text, named) ?: Request.Other(text))

    @Test
    fun aConfirmationGoesAheadOnConfirmYesOrSendIt() {
        for (yes in listOf("Confirm.", "Yes", "Send it!")) assertEquals(yes, ChannelAnswer.Yes, read(QuestionKind.CONFIRM, yes))
        assertEquals(ChannelAnswer.Yes, read(QuestionKind.CONFIRM, "Kestrel, confirm", named = true))
        assertEquals(ChannelAnswer.No, read(QuestionKind.CONFIRM, "No"))
        assertEquals(null, read(QuestionKind.CONFIRM, "Go on."))
    }

    @Test
    fun theOtherYesOrNoQuestionsTakeYesContinueOrGoOn() {
        for (kind in listOf(QuestionKind.YES_NO, QuestionKind.CONTINUE_GOAL, QuestionKind.LIMIT, QuestionKind.SWITCH)) {
            for (yes in listOf("Yes.", "Yes, please.", "Continue", "Go on.")) assertEquals("$kind $yes", ChannelAnswer.Yes, read(kind, yes))
            assertEquals("$kind", ChannelAnswer.No, read(kind, "No."))
            assertEquals("$kind", null, read(kind, "Open Viber"))
        }
    }

    @Test
    fun continueOrGoOnNeverDeletesTheHistory() {
        assertEquals(ChannelAnswer.Yes, read(QuestionKind.DELETE_HISTORY, "Yes, please."))
        assertEquals(ChannelAnswer.No, read(QuestionKind.DELETE_HISTORY, "No."))
        for (other in listOf("Continue", "Go on.", "Confirm.", "Send it")) assertEquals(other, null, read(QuestionKind.DELETE_HISTORY, other))
        assertEquals(null, read(QuestionKind.DELETE_HISTORY, "Kestrel, continue", named = true))
    }

    @Test
    fun aFreeQuestionTakesTheWordsAndAPickANumber() {
        assertEquals(ChannelAnswer.Text("The blue one."), read(QuestionKind.FREE, "The blue one."))
        assertEquals(ChannelAnswer.Text("Yes."), read(QuestionKind.SCREENSHOT, "Yes."))
        assertEquals(ChannelAnswer.Pick(12), read(QuestionKind.PICK, "12"))
        assertEquals(null, read(QuestionKind.PICK, "the red one"))
    }
}
