package dev.kestrel.app.commands

import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAppsTest {
    private class App(val label: String, val work: Boolean)

    private val chrome = App("Chrome", work = false)
    private val workChrome = App("Chrome", work = true)
    private val gmail = App("Gmail", work = false)
    private val clock = App("Clock", work = false)
    private val photos = App("Photos", work = false)
    private val editor = App("Photo Editor", work = false)
    private val apps = listOf(workChrome, chrome, gmail, clock, photos, editor, App("Phone", work = false))

    private fun pick(spoken: String, items: List<App> = apps) = pick(spoken, items, { it.label }, { !it.work })

    @Test
    fun theOwnersAppStandsForTheSameAppInTheWorkProfile() {
        assertEquals(Pick.One(gmail), pick("Gmail"))
        assertEquals(Pick.One(chrome), pick("chrome"))
        assertEquals(Pick.One(workChrome), pick("Chrome", listOf(workChrome, gmail)))
        assertEquals(Pick.None, pick("Quorbex Zyntal"))
    }

    @Test
    fun twoAppsOfTheOwnerWithTheSameNameAreBothOffered() {
        val otherPhone = App("Phone", work = false)
        val phone = App("Phone", work = false)

        assertEquals(Pick.Several(listOf(phone, otherPhone)), pick("Phone", listOf(phone, otherPhone, gmail)))
    }

    @Test
    fun closeNamesAreOfferedAndAnAnswerIsANumberOrOneOfTheNames() = runTest {
        assertEquals(Pick.Several(listOf(photos, editor)), pick("Photo"))
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
