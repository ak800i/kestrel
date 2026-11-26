package dev.kestrel.app.conversation

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The owner's typed words on Kestrel's own screen (design 12.2 of sub-project 7). */
class TypedInputsTest {
    private val idle = ConversationView()

    // The open question of the conversation's view, number 3.
    private fun asking(kind: QuestionKind) = ConversationView(asking = kind, question = 3)

    @Test
    fun withoutAQuestionKestrelsOwnRequestsAreReadWithoutTheNameAndTheRestIsACommand() {
        assertEquals(InputIntent.Command("Read my latest SMS"), TypedInputs.read("  Read my latest SMS ", idle))
        assertEquals(InputIntent.NewConversation, TypedInputs.read("new conversation", idle))
        assertEquals(InputIntent.DeleteHistory, TypedInputs.read("delete history", idle))
        assertEquals(InputIntent.Continue, TypedInputs.read("continue", idle))
        assertEquals(InputIntent.Stop, TypedInputs.read("stop", idle))
        assertNull(TypedInputs.read("   ", idle))
    }

    // Plan 7D's typed reading: only the exact name counts, and the voice's own requests stay the owner's words.
    @Test
    fun onlyTheExactNameCountsAndTheVoicesOwnRequestsStayTheOwnersWords() {
        assertEquals(InputIntent.Command("read my latest SMS"), TypedInputs.read("Kestrel, read my latest SMS", idle))
        assertEquals(InputIntent.Command("Kestral, open Viber"), TypedInputs.read("Kestral, open Viber", idle))
        assertEquals(InputIntent.Command("never mind"), TypedInputs.read("never mind", idle))
        assertEquals(InputIntent.Command("forget it"), TypedInputs.read("forget it", idle))
        assertEquals(InputIntent.Command("turn off"), TypedInputs.read("turn off", idle))
        assertEquals(InputIntent.Stop, TypedInputs.read("Stop it, Kestrel", idle))
    }

    // Design D2 of plan 7D: an answer names the question that it answers, so that it never answers a later one.
    @Test
    fun whileAQuestionWaitsTheWordsAnswerItAsTheVoiceReadsThemAndNameIt() {
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 3), TypedInputs.read("Yes", asking(QuestionKind.YES_NO)))
        assertEquals(InputIntent.Answer(ChannelAnswer.No, question = 3), TypedInputs.read("no", asking(QuestionKind.DELETE_HISTORY)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Text("Mila Petrović"), question = 3), TypedInputs.read("Mila Petrović", asking(QuestionKind.FREE)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Text("nothing"), question = 3), TypedInputs.read("nothing", asking(QuestionKind.FREE)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Pick(2), question = 3), TypedInputs.read("2", asking(QuestionKind.PICK)))
        assertEquals(InputIntent.Answer(ChannelAnswer.Yes, question = 3), TypedInputs.read("continue", asking(QuestionKind.CONTINUE_GOAL)))
        // "Stop" and "cancel" stop the goal also while it asks.
        assertEquals(InputIntent.Stop, TypedInputs.read("cancel", asking(QuestionKind.FREE)))
    }

    @Test
    fun aConfirmationIsNeverAnsweredByTypedWords() {
        // A confirmation is always a button (design 10.3): the words reach it as a command, which counts as no.
        assertEquals(InputIntent.Command("yes"), TypedInputs.read("yes", asking(QuestionKind.CONFIRM)))
        assertEquals(InputIntent.Command("confirm"), TypedInputs.read("confirm", asking(QuestionKind.CONFIRM)))
    }
}
