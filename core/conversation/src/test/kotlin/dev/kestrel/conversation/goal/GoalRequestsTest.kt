package dev.kestrel.conversation.goal

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.agent.goal.ResumeNote
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.goal.SteerChannel
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.context.ContextBlock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.LineKind
import dev.kestrel.conversation.model.RecordLine
import dev.kestrel.conversation.model.RecordRef
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.safety.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A round's request (design 7.1, 7.6, 8.4 and 12.1 of sub-project 7; plan 7A's "Interfaces for 7B"). */
class GoalRequestsTest {
    private val id = ConversationId("0123456789abcdef0123456789abcdef")
    private val limits = GoalLimits(replies = 50, workMinutes = 5, credits = 10)
    private val earlier = CommandRecord(
        number = 1,
        atWall = ManualConversationClock.BASE,
        channel = InputChannel.VOICE,
        request = "Send Ana the photo",
        lines = listOf(
            RecordLine(LineKind.KESTREL_ASKED, "Which Ana?"),
            RecordLine(LineKind.OWNER_ANSWERED, "Ana Jović", InputChannel.VOICE),
            RecordLine(LineKind.OWNER_STEERED, "and Mila too", InputChannel.TEXT),
            RecordLine(LineKind.OWNER_CONTINUED, "continue", InputChannel.TEXT),
        ),
        answer = "Sent.",
        end = EndReason.FINISHED,
        allowance = setOf("sending"),
    )

    private fun plan(record: CommandRecord, earlier: List<CommandRecord>, resume: ResumeCause? = null, continuedAtWall: Long? = null, text: String = record.request, waiting: Int = 0) =
        RoundPlan(
            record, earlier, text, resume, testMode = true, continuedAtWall = continuedAtWall, limits = limits, options = emptyMap(),
            self = RecordRef(id, record.number), zone = ManualConversationClock.BELGRADE, waiting = waiting, stored = {},
        )

    @Test
    fun aFirstCommandHasNoBlockAndNoEarlierWords() {
        val first = CommandRecord(1, 0, InputChannel.TEXT, "Turn on Wi-Fi")

        val request = GoalRequests.request(plan(first, emptyList(), text = "Turn on Wi-Fi"), "task-1-0a1b2c3d", setOf("Ana"), ContextBlock.BUDGET)

        assertEquals(TaskRequest("task-1-0a1b2c3d", "Turn on Wi-Fi", setOf("Ana"), limits = limits), request)
    }

    @Test
    fun aLaterCommandGetsTheBlockTheOwnersWordsAndTheCarriedAllowance() {
        val second = CommandRecord(2, 0, InputChannel.TEXT, "And to Marko")

        val request = GoalRequests.request(plan(second, listOf(earlier), text = "And to Marko"), "task-2-0a1b2c3d", emptySet(), ContextBlock.BUDGET)

        assertTrue(request.conversation!!.text.startsWith(TaskRequest.CONVERSATION_HEADER))
        assertEquals(listOf("Send Ana the photo", "Ana Jović", "and Mila too"), request.ownerHistory)
        assertEquals(setOf(Category.SENDING), request.carried)
        assertEquals("And to Marko", request.text)
        assertNull(request.resume)
    }

    @Test
    fun aResumedRoundCarriesTheChecklistTheLedgerAndTheGoalsOwnInputs() {
        val checklist = Checklist(listOf(ChecklistItem("c1", "Open Gmail", ItemState.DONE, step = 2), ChecklistItem("c2", "Archive the newsletters", ItemState.DOING)))
        val goal = CommandRecord(
            number = 2,
            atWall = 0,
            channel = InputChannel.TEXT,
            request = "Archive my newsletters",
            lines = listOf(
                RecordLine(LineKind.OWNER_STEERED, "only the ones from 2025", InputChannel.TEXT),
                RecordLine(LineKind.OWNER_CONTINUED, "continue", InputChannel.VOICE),
                RecordLine(LineKind.OWNER_STEERED, "and the ones from Mila", InputChannel.VOICE),
            ),
            ledger = listOf("opened Gmail"),
            checklist = checklist,
            allowance = setOf("deleting"),
        )

        // The last steer still waits in the goal's inbox, which gives it to the run before its first step.
        val request = GoalRequests.request(plan(goal, listOf(earlier), resume = ResumeCause.INTERRUPTION, waiting = 1), "task-3-0a1b2c3d", emptySet(), ContextBlock.BUDGET)

        assertEquals("Archive my newsletters", request.text)
        assertEquals(ResumeNote(ResumeCause.INTERRUPTION, listOf("opened Gmail"), listOf(Steer("only the ones from 2025", SteerChannel.TYPED))), request.resume)
        assertEquals(checklist, request.checklist)
        assertEquals(listOf("Send Ana the photo", "Ana Jović", "and Mila too", "only the ones from 2025", "continue", "and the ones from Mila"), request.ownerHistory)
        assertEquals(setOf(Category.SENDING, Category.DELETING), request.carried)
        assertTrue(!request.conversation!!.text.contains("Archive my newsletters"))
    }

    @Test
    fun aContinuedConversationsBlockSaysSoWithTheCurrentTestMode() {
        val second = CommandRecord(2, 0, InputChannel.TEXT, "And to Marko")
        val at = ManualConversationClock.BASE

        val request = GoalRequests.request(plan(second, listOf(earlier), continuedAtWall = at), "task-2-0a1b2c3d", emptySet(), ContextBlock.BUDGET)

        assertEquals(ConversationTexts.continuedNote(at, ManualConversationClock.BELGRADE, testMode = true), request.conversation!!.text.lines()[1])
        assertEquals("This conversation was continued on Friday, 20 November 2026; test mode is on now.", request.conversation!!.text.lines()[1])
    }
}
