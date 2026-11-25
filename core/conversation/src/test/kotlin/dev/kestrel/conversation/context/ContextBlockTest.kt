package dev.kestrel.conversation.context

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.RecordLine
import dev.kestrel.safety.Placeholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ContextBlockTest {
    private val zone = ZoneId.of("Europe/Belgrade")

    private fun record(
        number: Int,
        request: String = "Command $number",
        data: List<DataNote> = emptyList(),
        lines: List<RecordLine> = emptyList(),
        ledger: List<String> = emptyList(),
        checklist: Checklist? = null,
    ) = CommandRecord(
        number = number,
        atWall = number * 60_000L,
        channel = InputChannel.TEXT,
        request = request,
        lines = lines,
        answer = "Answer $number",
        end = EndReason.FINISHED,
        data = data,
        apps = listOf(AppUse("Viber", "com.viber.voip")),
        ledger = ledger,
        checklist = checklist,
    )

    private fun text(earlier: List<CommandRecord>, budget: Int = ContextBlock.BUDGET, note: String? = null): String =
        ContextBlock.build(earlier, budget, zone, note)!!.text

    @Test
    fun aFirstCommandHasNoBlock() {
        assertNull(ContextBlock.build(emptyList(), ContextBlock.BUDGET, zone))
    }

    @Test
    fun theNewestRecordShowsItsCapsAndTheOlderOnesAreCondensed() {
        val block = text(listOf(record(1, data = listOf(DataNote("list_sms", 1, "a\nb"))), record(2, request = "x".repeat(500))))

        assertEquals(TaskRequest.CONVERSATION_HEADER, block.lines().first())
        assertTrue(block.contains("  The owner said: \"${"x".repeat(400)}…\""))
        assertTrue(block.contains("  Data: list_sms gave 2 lines."))
        assertTrue(block.indexOf("Command 1, at 01:01") < block.indexOf("Command 2, at 01:02"))
        assertTrue(block.contains("  Kestrel answered: \"Answer 2\""))
        assertTrue(block.contains("  It ended: finished."))
        assertTrue(block.contains("  Apps: Viber"))
    }

    @Test
    fun theChecklistShowsItsOpenItemsAndCountsTheClosedOnes() {
        val closed = (1..13).map { ChecklistItem("c$it", "Step $it", if (it == 13) ItemState.SKIPPED else ItemState.DONE, step = it, why = if (it == 13) "Not needed" else null) }
        val open = listOf(ChecklistItem("c14", "Reply to Mila", ItemState.DOING), ChecklistItem("c15", "Archive the thread", ItemState.TODO))

        val block = text(listOf(record(1, checklist = Checklist(closed + open))))

        assertTrue(block.contains("  Checklist: 12 done, 1 skipped, 0 failed; open:\n    c14 [doing] \"Reply to Mila\"\n    c15 [todo] \"Archive the thread\""))
        assertFalse(block.contains("Step 3"))
    }

    @Test
    fun theFillOrderLeavesOutTheLatePartsFirstAndCountsThem() {
        for (letter in listOf("d", "ж")) {
            val items = (1..12).map { ChecklistItem("c$it", letter.repeat(120), ItemState.TODO) }
            val newest = record(
                4,
                data = (1..5).map { DataNote("list_sms", it, letter.repeat(600)) },
                lines = (1..10).map { RecordLine(LineKind.KESTREL_SAID, letter.repeat(300)) },
                ledger = (1..5).map { letter.repeat(200) },
                checklist = Checklist(items),
            )
            val earlier = listOf(record(1), record(2), record(3), newest)

            val block = text(earlier)

            assertTrue(block.contains("(3 earlier commands of this conversation are left out; search_conversations finds them.)"))
            assertTrue(Regex("  \\(\\d+ earlier lines of this command are left out\\.\\)").containsMatchIn(block))
            assertTrue(block.contains("  Data from list_sms at step 5: "))
            assertEverythingShownOrCounted(block, earlier)
            assertTrue("$letter: ${ScreenText.tokens(block)}", ScreenText.tokens(block) <= ContextBlock.BUDGET)
        }
    }

    @Test
    fun theBlockNeverPassesItsBudgetAndCountsWhatItLeavesOutAlsoWithTheLargestRecordsAndInCyrillic() {
        for (letter in listOf('w', 'ж')) {
            val long = letter.toString().repeat(2_000)
            val items = (1..30).map { ChecklistItem("c$it", letter.toString().repeat(120), if (it <= 10) ItemState.DONE else ItemState.TODO, step = if (it <= 10) it else null) }
            val largest = CommandRecord(
                number = 1,
                atWall = 0,
                channel = InputChannel.VOICE,
                request = long,
                lines = (1..200).map { RecordLine(LineKind.OWNER_STEERED, long, InputChannel.TEXT) },
                answer = long,
                end = EndReason.STOPPED_AT_LIMIT,
                data = (1..20).map { DataNote("list_notifications", it, long) },
                apps = (1..8).map { AppUse(long.take(40), "app.$it") },
                ledger = (1..50).map { long },
                checklist = Checklist(items),
                droppedLines = 3,
                droppedData = 2,
            )
            val earlier = (1..7).map { largest.copy(number = it) }
            for (budget in listOf(ContextBlock.BUDGET, 600, 256)) {
                val block = text(earlier, budget, note = "This conversation was continued on Friday, 20 November 2026; test mode is on now.")
                assertTrue("$letter $budget: ${ScreenText.tokens(block)}", ScreenText.tokens(block) <= budget)
                assertTrue(block.startsWith(TaskRequest.CONVERSATION_HEADER))
                assertEverythingShownOrCounted(block, earlier)
            }
        }
    }

    // Design 8.3: whatever a part of the block leaves out, one line counts it, so the shown and the counted add up to the
    // whole. The newest record's lines here are only of the kinds that say "Kestrel said" or "The owner added".
    private fun assertEverythingShownOrCounted(block: String, earlier: List<CommandRecord>) {
        val newest = earlier.last()
        val heads = Regex("^Command \\d+, at ", RegexOption.MULTILINE).findAll(block).count()
        assertEquals("records", earlier.size, heads + counted(block, "\\((\\d+) earlier commands? of this conversation (?:is|are) left out; search_conversations finds them\\.\\)"))
        if (heads == 0) return
        val rows = block.lines()
        assertEquals("open items", newest.checklist?.open.orEmpty().size, rows.count { it.matches(Regex("^ {4}c\\d+ \\[.*")) } + counted(block, "\\(and (\\d+) more open items?\\)"))
        assertEquals("data notes", newest.data.size + newest.droppedData, rows.count { it.startsWith("  Data from ") } + counted(block, "\\((\\d+) earlier data notes? of this command (?:is|are) left out\\.\\)"))
        assertEquals("lines", newest.lines.size + newest.droppedLines, rows.count { it.startsWith("  Kestrel said: ") || it.startsWith("  The owner added (") } + counted(block, "\\((\\d+) earlier lines? of this command (?:is|are) left out\\.\\)"))
        assertEquals("ledger", newest.ledger.size, rows.count { it.startsWith("  Kestrel did: ") } + counted(block, "\\((\\d+) earlier ledger lines? of this command (?:is|are) left out\\.\\)"))
    }

    private fun counted(block: String, pattern: String): Int {
        val found = Regex(pattern).findAll(block).toList()
        assertTrue("one line counts them", found.size <= 1)
        return found.singleOrNull()?.groupValues?.get(1)?.toInt() ?: 0
    }

    @Test
    fun aLocalOllamaModelGetsAnEighthOfItsWindow() {
        val info = ScriptedModel.INFO
        val small = info.copy(localOllama = true, lightweight = info.lightweight.copy(contextWindow = 8_192), versatile = info.versatile.copy(contextWindow = 16_384))
        val large = info.copy(localOllama = true, lightweight = info.lightweight.copy(contextWindow = 32_768), versatile = info.versatile.copy(contextWindow = 32_768))

        assertEquals(ContextBlock.BUDGET, ContextBlock.budget(info))
        assertEquals(1_024, ContextBlock.budget(small))
        assertEquals(ContextBlock.BUDGET, ContextBlock.budget(large))
    }

    @Test
    fun aCondensedRecordStaysCondensedAfterAContinue() {
        val second = record(2, request = "z".repeat(300))
        val before = RecordCondenser.condense(listOf(record(1), second))
        val blockBefore = text(before)
        // The conversation is closed, continued, and its next command ends: the earlier records are condensed again.
        val after = RecordCondenser.condense(before + record(3))
        val blockAfter = text(after)
        val first = RecordCondenser.lines(before[0], zone).joinToString("\n")

        assertEquals(listOf(true, true, false), after.map { it.condensed })
        assertTrue(blockBefore.contains(first))
        assertTrue(blockAfter.contains(first))
        assertTrue(blockBefore.contains("\"${"z".repeat(300)}\""))
        assertTrue(blockAfter.contains("\"${"z".repeat(200)}…\""))
    }

    @Test
    fun aContinuedConversationsBlockStartsWithItsNote() {
        val note = "This conversation was continued on Friday, 20 November 2026; test mode is off now."

        assertEquals(note, text(listOf(record(1)), note = note).lines()[1])
        assertEquals("${TaskRequest.CONVERSATION_HEADER}\n$note", text(emptyList(), note = note))
    }

    @Test
    fun noRecordHoldsAPlaceholderOrAValue() {
        val redacted = RecordText.of("The code is 482913")
        val block = text(listOf(record(1, request = redacted, data = listOf(DataNote("list_sms", 1, "body: \"$redacted\"")))))

        assertFalse(block.contains("482913"))
        assertFalse(Placeholders.PATTERN.containsMatchIn(block))
    }
}
