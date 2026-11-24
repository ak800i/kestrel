package dev.kestrel.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** D13's allowance by verbs in a command position (decision 6; research r52), with the requests that allow nothing. */
class TaskAllowanceTest {
    private fun allowed(request: String, knownNames: Set<String> = emptySet()) = TaskAllowance.from(request, knownNames).categories

    @Test
    fun onlySendingCallsAndDeletingCarryToALaterCommand() {
        assertEquals(setOf(Category.SENDING, Category.CALLS, Category.DELETING), TaskAllowance.carried(Category.entries.toSet()).categories)
        assertEquals(TaskAllowance.NONE, TaskAllowance.carried(Category.entries.toSet() - TaskAllowance.CARRIES))
    }

    @Test
    fun theSixTasksOfTheDesign() {
        assertEquals(setOf(Category.SENDING), allowed("Send Ana a Viber message that I'm 10 minutes late"))
        assertEquals(emptySet<Category>(), allowed("Turn on hotspot"))
        assertEquals(emptySet<Category>(), allowed("Start Waze navigation to home"))
        assertEquals(emptySet<Category>(), allowed("Open a web page via Firefox and interact with it"))
        assertEquals(setOf(Category.SENDING), allowed("Send yesterday's photos to John on Telegram"))
        assertEquals(emptySet<Category>(), allowed("Read my latest email back to me"))
    }

    @Test
    fun nounsAndWordsOutsideACommandPositionAllowNothing() {
        for (request in listOf(
            "Read my latest message", "What did Ana text me?", "Tell me my next meeting", "Who was my last call?",
            "Answer the quiz", "Text size bigger", "Forward 30 seconds", "Fast forward the song", "Message the group",
        )) {
            assertEquals(request, emptySet<Category>(), allowed(request))
        }
    }

    @Test
    fun aNameCountsWithAMessageAfterItOrWhenMemoryOrTheContactsKnowIt() {
        assertEquals(setOf(Category.SENDING), allowed("Message Ana that I'm late"))
        assertEquals(setOf(Category.SENDING), allowed("Text Marko: running late"))
        assertEquals(setOf(Category.SENDING), allowed("Javi Ani da kasnim"))
        assertEquals(emptySet<Category>(), allowed("Call mom"))
        assertEquals(setOf(Category.CALLS), allowed("Call mom", setOf("Mom")))
        assertEquals(setOf(Category.SENDING), allowed("E-mail Ana Petrović about the plan"))
    }

    @Test
    fun severalVerbsAfterAndThenPleaseOrKestrel() {
        assertEquals(setOf(Category.SENDING, Category.DELETING), allowed("Kestrel, delete the draft and then reply to Ana"))
        assertEquals(setOf(Category.CALLS), allowed("Please dial the office"))
        assertEquals(setOf(Category.DELETING), allowed("Clear history"))
        assertEquals(setOf(Category.SENDING), allowed("Pošalji Ani poruku"))
        assertEquals(setOf(Category.SENDING), allowed("Post a photo of the sunset"))
    }

    @Test
    fun readingTypingAndSettingsAreAlwaysAllowedAndScreenTextOnlyWithSending() {
        val none = TaskAllowance.NONE
        assertTrue(none.allows(Category.READING) && none.allows(Category.TYPING_REQUEST) && none.allows(Category.ORDINARY_SETTINGS))
        assertFalse(none.allows(Category.TYPING_SCREEN_TEXT))
        assertTrue(TaskAllowance(setOf(Category.SENDING)).allows(Category.TYPING_SCREEN_TEXT))
        assertFalse(TaskAllowance(setOf(Category.SENDING, Category.DELETING)).allows(Category.PAYMENTS))
        assertEquals("This task may send messages and delete things after the owner confirms.", TaskAllowance(setOf(Category.SENDING, Category.DELETING)).describe())
        assertEquals("This task may not send, call or delete anything without asking the owner.", none.describe())
    }
}
