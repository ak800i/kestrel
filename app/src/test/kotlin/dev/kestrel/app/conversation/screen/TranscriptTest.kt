package dev.kestrel.app.conversation.screen

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.RecordLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The transcript that the conversation screen draws from records (design 10.1 of sub-project 7). */
class TranscriptTest {
    @Test
    fun aRecordShowsTheOwnersCommandKestrelsLinesItsChecklistAndItsAnswerInOrder() {
        val record = CommandRecord(
            number = 1,
            atWall = 1_000,
            channel = InputChannel.VOICE,
            request = "Plan the trip",
            lines = listOf(
                RecordLine(LineKind.KESTREL_ASKED, "Which day?"),
                RecordLine(LineKind.OWNER_ANSWERED, "Friday", InputChannel.TEXT),
                RecordLine(LineKind.KESTREL_SAID, "On it."),
                RecordLine(LineKind.OWNER_STEERED, "Also book a hotel", InputChannel.VOICE),
                RecordLine(LineKind.OWNER_TOO_LATE, "And a car", InputChannel.VOICE),
                RecordLine(LineKind.OWNER_CONTINUED, "continue", InputChannel.TEXT),
                RecordLine(LineKind.NOTE, "A routine ran"),
            ),
            answer = "Done.",
            checklist = Checklist(listOf(ChecklistItem("c1", "Find a train", ItemState.DONE, step = 3), ChecklistItem("c2", "Book a hotel", ItemState.DOING))),
            droppedLines = 2,
        )

        assertEquals(
            listOf(
                TranscriptLine.Owner(OwnerLine.COMMAND, "Plan the trip", InputChannel.VOICE, 1_000),
                TranscriptLine.LeftOut(lines = 2, data = 0),
                TranscriptLine.Kestrel("Which day?", asked = true),
                TranscriptLine.Owner(OwnerLine.ANSWER, "Friday", InputChannel.TEXT),
                TranscriptLine.Kestrel("On it.", asked = false),
                TranscriptLine.Owner(OwnerLine.STEERING, "Also book a hotel", InputChannel.VOICE),
                TranscriptLine.Owner(OwnerLine.TOO_LATE, "And a car", InputChannel.VOICE),
                TranscriptLine.Owner(OwnerLine.CONTINUE, "continue", InputChannel.TEXT),
                TranscriptLine.Note("A routine ran"),
                TranscriptLine.Checklist(listOf(ChecklistRow("Find a train", ItemState.DONE), ChecklistRow("Book a hotel", ItemState.DOING)), done = 1),
                TranscriptLine.Answer("Done."),
            ),
            Transcript.of(listOf(record)),
        )
    }

    @Test
    fun aContinuedConversationShowsWhereItWasContinuedAndAnEndedOneWhereItEnded() {
        val old = CommandRecord(1, 1_000, InputChannel.TEXT, "Find the restaurant")
        val new = CommandRecord(2, 90_000, InputChannel.VOICE, "What was its address?")

        val lines = Transcript.of(listOf(old, new), continuedAtWall = 80_000, endedAtWall = 95_000)

        assertEquals(TranscriptLine.Continued(80_000), lines[1])
        assertEquals(TranscriptLine.Owner(OwnerLine.COMMAND, "What was its address?", InputChannel.VOICE, 90_000), lines[2])
        assertEquals(TranscriptLine.Ended(95_000), lines.last())
        // Continued with no command since: the line comes after the last command.
        assertEquals(
            listOf(TranscriptLine.Owner(OwnerLine.COMMAND, "Find the restaurant", InputChannel.TEXT, 1_000), TranscriptLine.Continued(80_000)),
            Transcript.of(listOf(old), continuedAtWall = 80_000),
        )
    }

    @Test
    fun linesPrintNoWords() {
        val record = CommandRecord(
            number = 1,
            atWall = 0,
            channel = InputChannel.TEXT,
            request = "Text Mila the secret",
            lines = listOf(RecordLine(LineKind.KESTREL_SAID, "a secret said")),
            answer = "a secret answer",
            checklist = Checklist(listOf(ChecklistItem("c1", "a secret step", ItemState.TODO))),
        )

        assertFalse(Transcript.of(listOf(record)).joinToString().contains("secret"))
    }
}
