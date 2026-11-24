package dev.kestrel.agent.loop

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ChecklistText
import dev.kestrel.agent.goal.ItemState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design 7.4 of sub-project 7: the question at a round's limit, built by code from the totals, the checklist and the ledger. */
class LimitQuestionTest {
    private val checklist = Checklist(listOf(
        ChecklistItem("c1", "Open Viber", ItemState.DONE, step = 2),
        ChecklistItem("c2", "Find Mila's chat", ItemState.DONE, step = 4),
        ChecklistItem("c3", "Read her message", ItemState.DONE, step = 6),
        ChecklistItem("c4", "Find the restaurant", ItemState.DONE, step = 9),
        ChecklistItem("c5", "reply to Mila", ItemState.DOING),
        ChecklistItem("c6", "archive the thread", ItemState.TODO),
        ChecklistItem("c7", "Tell Ana", ItemState.SKIPPED, why = "Ana is away"),
    ))

    @Test
    fun theQuestionNamesTheLimitThatWasReachedAndTheChecklistsProgress() {
        val rest = "Done: 4 of 7 steps. Left: reply to Mila, archive the thread. Continue?"

        assertEquals("I've worked for 60 minutes on this. $rest", LimitQuestion.text(Limit.TIME, 120, 3_612_000, 30.0, checklist, emptyList()))
        assertEquals("I've used 1,000 model replies on this. $rest", LimitQuestion.text(Limit.REPLIES, 1_000, 0, 0.0, checklist, emptyList()))
        assertEquals("This has used 1,012 AI Credits. $rest", LimitQuestion.text(Limit.CREDITS, 9, 0, 1_012.7, checklist, emptyList()))
    }

    @Test
    fun withoutAChecklistItNamesTheNewestLinesOfTheLedger() {
        assertEquals(
            "I've used 30 model replies on this. I opened Viber and typed the message. Continue?",
            LimitQuestion.text(Limit.REPLIES, 30, 0, 0.0, null, listOf("opened Viber", "typed the message")),
        )
        assertEquals(
            "I've used 30 model replies on this. Most recently, I typed the message, sent the message to Ana and opened Telegram. Continue?",
            LimitQuestion.text(Limit.REPLIES, 30, 0, 0.0, null, listOf("opened Viber", "typed the message", "sent the message to Ana", "opened Telegram")),
        )
        assertEquals("I've worked for 1 minute on this. I haven't changed anything yet. Continue?", LimitQuestion.text(Limit.TIME, 3, 61_000, 0.0, null, emptyList()))
    }

    @Test
    fun aLongChecklistNamesThreeOpenItemsAndCountsTheRest() {
        val open = Checklist(listOf("a", "b", "c", "d", "e").mapIndexed { index, text -> ChecklistItem("c${index + 1}", text, ItemState.TODO) })

        assertEquals("Done: 0 of 5 steps. Left: a, b, c and 2 more.", ChecklistText.progress(open))
        assertEquals("Done: 1 of 1 step.", ChecklistText.progress(Checklist(listOf(ChecklistItem("c1", "a", ItemState.DONE, step = 1)))))
    }
}
