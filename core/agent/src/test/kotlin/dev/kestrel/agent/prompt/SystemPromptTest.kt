package dev.kestrel.agent.prompt

import dev.kestrel.agent.OwnerLines
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.tools.Tools
import dev.kestrel.safety.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptTest {
    @Test
    fun thePromptEqualsItsGoldenFile() {
        val golden = SystemPromptTest::class.java.getResource("/golden/system-prompt.txt")!!.readText()

        assertEquals(golden, SystemPrompt.TEXT + "\n")
    }

    @Test
    fun thePromptNamesEveryCategoryAndHoldsNothingThatChangesPerTask() {
        Category.entries.forEach { assertTrue(it.wire, SystemPrompt.TEXT.contains(it.wire)) }
        assertFalse(Regex("""\d{1,2}:\d{2}|20\d\d""").containsMatchIn(SystemPrompt.TEXT))
        assertTrue(SystemPrompt.TEXT.length in 3_500..7_500)
    }

    // Plan 7A: the prompt names the agent's own markers exactly as the run writes them.
    @Test
    fun thePromptNamesTheChecklistTheSearchTheRecordsAndTheOwnersMessages() {
        for (marker in listOf(Tools.UPDATE_CHECKLIST, Tools.SEARCH_CONVERSATIONS, TaskRequest.CONVERSATION_HEADER)) {
            assertTrue(marker, SystemPrompt.TEXT.contains(marker))
        }
    }

    // The rule that tells the owner's words from data names every opening of the owner's lines, in quotes.
    @Test
    fun theUntrustedTextRuleNamesEveryOpeningOfTheOwnersLines() {
        val rule = SystemPrompt.TEXT.lines().single { it.startsWith("- The owner's own words are the quoted words on a line that starts") }

        for (marker in OwnerLines.MARKERS) assertTrue(marker, rule.contains("\"$marker\""))
    }
}
