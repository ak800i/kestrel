package dev.kestrel.app.commands

import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Which one?" after "open <app>"; the matcher itself is `NamePick` of `:core:commands`, tested in `NamePickTest`. */
class PhoneAppsTest {
    @Test
    fun anAnswerIsANumberOrOneOfTheNames() = runTest {
        val names = listOf("Photos", "Photo Editor")

        assertEquals(Answer.Chosen(1), askAmong(Answers("2"), PhoneCommandTexts.whichOne(names), names))
        assertEquals(Answer.Chosen(1), askAmong(Answers("photo editor"), "Which one?", names))
        assertEquals(Answer.Chosen(1), askAmong(Answers("to"), "Which one?", names))
    }

    @Test
    fun anAnswerThatIsNoChoiceEndsTheCommand() = runTest {
        val names = listOf("Photos", "Photo Editor")

        assertEquals(Answer.Ends(CommandResult(Outcome.CANCELLED, passOn = "scroll down")), askAmong(Answers("scroll down"), "Which one?", names))
        assertEquals(Answer.Ends(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.noChoice(3))), askAmong(Answers("3"), "Which one?", names))
        assertEquals(Answer.Ends(CommandResult(Outcome.CANCELLED)), askAmong(Answers(null), "Which one?", names))
        assertEquals("Which one? 1, Photos. 2, Photo Editor.", PhoneCommandTexts.whichOne(names))
        // Two offered items with the same name: the name chooses neither, and it is no new utterance.
        assertEquals(
            Answer.Ends(CommandResult(Outcome.FAILED, show = PhoneCommandTexts.SAY_THE_NUMBER)),
            askAmong(Answers("phone"), "Which one?", listOf("Phone", "Phone")),
        )
    }

    /** Answers each question with the next of [answers]; null is a question that nobody answered. */
    private class Answers(private vararg val answers: String?) : CommandConversation {
        private var next = 0

        override suspend fun say(text: String) = Unit

        override suspend fun ask(question: String): String? = answers[next++]

        override fun keep(text: String) = Unit

        override suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean = true
    }
}
