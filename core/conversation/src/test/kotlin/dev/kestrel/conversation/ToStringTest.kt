package dev.kestrel.conversation

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.conversation.model.PendingSteer
import dev.kestrel.conversation.model.RecordLine
import org.junit.Assert.assertFalse
import org.junit.Test

/** The model's types print kinds, numbers and lengths, never the owner's words or a record's text (design D25 of sub-project 5). */
class ToStringTest {
    @Test
    fun theModelsTypesPrintNoWords() {
        val marker = "Mila-7Q"
        val record = CommandRecord(
            number = 1,
            atWall = 0,
            channel = InputChannel.VOICE,
            request = marker,
            lines = listOf(RecordLine(LineKind.KESTREL_SAID, marker)),
            answer = marker,
            data = listOf(DataNote(marker, 1, marker, items = 1)),
            apps = listOf(AppUse(marker, marker)),
            ledger = listOf(marker),
            checklist = Checklist(listOf(ChecklistItem("c1", marker, ItemState.TODO))),
        )
        val values = listOf(
            OwnerInput(1, InputChannel.TEXT, InputIntent.Command(marker), 0, mapOf("model" to marker)),
            InputIntent.Answer(ChannelAnswer.Text(marker, leftScreen = true)),
            InputIntent.ContinuePast(marker),
            InputIntent.Remember(marker, marker),
            InputIntent.Forget(marker),
            InputIntent.SaveRoutine(marker),
            record,
            record.lines.single(),
            record.data.single(),
            record.apps.single(),
            PendingSteer(marker, InputChannel.TEXT),
            ConversationSnapshot(ConversationId("0123456789abcdef0123456789abcdef"), 0, 0, true, true, listOf(record), steering = listOf(PendingSteer(marker, InputChannel.TEXT))),
        )

        for (value in values) assertFalse(value.javaClass.simpleName, value.toString().contains(marker))
    }
}
