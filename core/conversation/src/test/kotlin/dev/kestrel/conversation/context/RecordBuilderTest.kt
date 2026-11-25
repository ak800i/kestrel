package dev.kestrel.conversation.context

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.safety.Category
import dev.kestrel.safety.Placeholders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class RecordBuilderTest {
    private val zone = ZoneId.of("Europe/Belgrade")

    private fun started(): CommandRecord = RecordBuilder.started(1, 0, InputChannel.TEXT, "Read Mila's message")

    @Test
    fun eachTraceLandsInItsPartOfTheRecord() {
        val entries = listOf(
            TraceEntry.Requested("Read Mila's last message"),
            TraceEntry.Said("Looking."),
            TraceEntry.Asked("Which Mila?"),
            TraceEntry.Answered("Mila Jović"),
            TraceEntry.Data("list_sms", 2, "from: \"Mila\""),
            TraceEntry.AppUsed("Viber", "com.viber.voip"),
            TraceEntry.AppUsed("Viber", "com.viber.voip"),
            TraceEntry.Did("opened Viber"),
        )

        val record = entries.fold(started()) { record, entry -> RecordBuilder.traced(record, entry, InputChannel.VOICE) }

        assertEquals("Read Mila's last message", record.request)
        assertEquals(listOf(LineKind.KESTREL_SAID, LineKind.KESTREL_ASKED, LineKind.OWNER_ANSWERED), record.lines.map { it.kind })
        assertNull(record.lines.first().channel)
        assertEquals(InputChannel.VOICE, record.lines.last().channel)
        assertEquals(listOf(DataNote("list_sms", 2, "from: \"Mila\"")), record.data)
        assertEquals(listOf(AppUse("Viber", "com.viber.voip")), record.apps)
        assertEquals(listOf("opened Viber"), record.ledger)
    }

    @Test
    fun aRoutinesStepsKeepTheirOwnNumbersAndTheModelsCountAcrossTheGoal() {
        // A routine's step 2 and, after it, the model's step 2 of a goal whose earlier rounds took 5 steps.
        val routine = RecordBuilder.traced(started(), TraceEntry.Data("list_sms", 2, "a"), InputChannel.TEXT, routine = true, stepsBefore = 5)
        val model = RecordBuilder.traced(routine, TraceEntry.Data("list_sms", 2, "b"), InputChannel.TEXT, stepsBefore = 5)

        assertEquals(listOf(DataNote("list_sms", 2, "a", routine = true), DataNote("list_sms", 7, "b")), model.data)
        assertEquals(
            listOf("  Data from list_sms at routine step 2: a", "  Data from list_sms at step 7: b"),
            model.data.map { RecordLines.data(it, 600) },
        )
    }

    @Test
    fun aQuestionAskedAgainAfterAnInterruptionIsALineEachTime() {
        val entries = listOf(TraceEntry.Asked("Send it to Ana?"), TraceEntry.Asked("Send it to Ana?"), TraceEntry.Answered("yes"))

        val record = entries.fold(started()) { record, entry -> RecordBuilder.traced(record, entry, InputChannel.VOICE) }

        assertEquals(listOf(LineKind.KESTREL_ASKED, LineKind.KESTREL_ASKED, LineKind.OWNER_ANSWERED), record.lines.map { it.kind })
    }

    @Test
    fun aRoundsReportGivesItsLedgerAlsoWhatTheTracesMissed() {
        // A first round's line, then a second round whose step sent the message as the round ended, before its trace.
        val traced = RecordBuilder.traced(started().copy(ledger = listOf("opened Gmail")), TraceEntry.Did("opened Viber"), InputChannel.TEXT)
        val sent = RecordBuilder.roundEnded(traced, from = 1, ledger = listOf("opened Viber", "sent the message to Ana"))
        assertEquals(listOf("opened Gmail", "opened Viber", "sent the message to Ana"), sent.ledger)

        // A share that Kestrel could not see go is only in the report.
        val pending = "tried to share the photos with Ana, but I couldn't see that they were sent"
        val shared = RecordBuilder.roundEnded(started(), from = 0, ledger = listOf(pending))
        assertEquals(listOf(pending), shared.ledger)

        // A report that the run did not make has no ledger, and the traced lines stay.
        assertEquals(listOf("opened Gmail", "opened Viber"), RecordBuilder.roundEnded(traced, from = 1, ledger = emptyList()).ledger)
    }

    @Test
    fun theRecordKeepsAtMost200LinesAnd20DataNotesAndCountsTheRest() {
        var record = started()
        repeat(205) { record = RecordBuilder.line(record, LineKind.KESTREL_SAID, "line $it", null) }
        repeat(23) { record = RecordBuilder.traced(record, TraceEntry.Data("list_sms", it, "note $it"), InputChannel.TEXT) }

        assertEquals(RecordBuilder.MAX_LINES, record.lines.size)
        assertEquals(5, record.droppedLines)
        assertEquals("line 5", record.lines.first().text)
        assertEquals(RecordBuilder.MAX_DATA, record.data.size)
        assertEquals(3, record.droppedData)
        assertEquals("note 3", record.data.first().text)
    }

    @Test
    fun aTextIsCutAt2000Characters() {
        val record = RecordBuilder.traced(started(), TraceEntry.Said("a".repeat(2_500)), InputChannel.TEXT)

        assertEquals(RecordBuilder.TEXT_LIMIT + 1, record.lines.single().text.length)
        assertTrue(record.lines.single().text.endsWith("…"))
    }

    @Test
    fun theEndTheChecklistAndTheAllowanceAreKept() {
        val checklist = Checklist(listOf(ChecklistItem("c1", "Open Viber", ItemState.DONE, step = 1)))

        val record = RecordBuilder.ended(
            RecordBuilder.allowed(RecordBuilder.checklist(started(), checklist), setOf(Category.SENDING, Category.CALLS)),
            EndReason.FINISHED,
            "Done.",
        )

        assertEquals(EndReason.FINISHED, record.end)
        assertEquals("Done.", record.answer)
        assertEquals(checklist, record.checklist)
        assertEquals(setOf("sending", "calls"), record.allowance)
    }

    @Test
    fun aRecordTextNamesAVaultValueByItsKind() {
        val text = RecordText.of("The code is 482913")

        assertFalse(text.contains("482913"))
        assertTrue(text, text.contains("a one-time code"))
        assertFalse(Placeholders.PATTERN.containsMatchIn(text))
    }

    @Test
    fun olderRecordsAreCondensedOnceAndStaySo() {
        val first = RecordCondenser.condense(listOf(started(), started().copy(number = 2), started().copy(number = 3)))
        val later = RecordCondenser.condense(first + started().copy(number = 4))

        assertEquals(listOf(true, true, false), first.map { it.condensed })
        assertEquals(listOf(true, true, true, false), later.map { it.condensed })
        assertEquals(first[0], later[0])
    }

    @Test
    fun aCondensedRecordShowsItsRequestAnswerEndAppsProgressAndOneLinePerDataNote() {
        val record = CommandRecord(
            number = 1,
            atWall = 0,
            channel = InputChannel.TEXT,
            request = "Read Mila's message",
            lines = listOf(RecordBuilder.line(started(), LineKind.KESTREL_SAID, "Looking.", null).lines.single()),
            answer = "She wrote: Ambar at 8.",
            end = EndReason.FINISHED,
            data = listOf(DataNote("list_sms", 1, "from: \"Mila\" · body: \"Ambar at 8\"\nfrom: \"Ana\" · body: \"Hi\"")),
            apps = listOf(AppUse("Viber", "com.viber.voip")),
            ledger = listOf("opened Viber"),
            checklist = Checklist(listOf(ChecklistItem("c1", "Open Viber", ItemState.DONE, step = 1), ChecklistItem("c2", "Reply to Mila", ItemState.TODO))),
        )

        assertEquals(
            listOf(
                "Command 1, at 01:00 (typed):",
                "  The owner said: \"Read Mila's message\"",
                "  Kestrel answered: \"She wrote: Ambar at 8.\"",
                "  It ended: finished.",
                "  Apps: Viber",
                "  Checklist: \"Done: 1 of 2 steps. Left: Reply to Mila.\"",
                "  Data: list_sms gave 2 lines.",
            ),
            RecordCondenser.lines(record, zone),
        )
    }
}
