package dev.kestrel.agent.goal

import dev.kestrel.agent.goal.ItemState.DOING
import dev.kestrel.agent.goal.ItemState.DONE
import dev.kestrel.agent.goal.ItemState.FAILED
import dev.kestrel.agent.goal.ItemState.SKIPPED
import dev.kestrel.agent.goal.ItemState.TODO
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design 7.2 of sub-project 7: the rules of an `update_checklist` call, checked by code. */
class ChecklistRulesTest {
    private fun draft(text: String, state: ItemState, id: String? = null, step: Int? = null, why: String? = null) = ChecklistDraft(id, text, state, step, why)

    // [current] is the checklist that the run started with, so its items came in before the run's steps.
    private fun check(drafts: List<ChecklistDraft>, current: Checklist? = null, steps: Set<Int> = emptySet(), step: Int = 1) =
        ChecklistRules.check(drafts, current?.let(ChecklistState::atStart), steps, step)

    private fun saved(check: ChecklistCheck) = check as ChecklistCheck.Saved

    private fun refusal(check: ChecklistCheck) = (check as ChecklistCheck.Refused).reason

    @Test
    fun aNewItemGetsTheNextIdAndAnItemWithAnIdKeepsIt() {
        val first = saved(check(listOf(draft("Open Viber", DOING), draft("Reply to Mila", TODO))))
        assertEquals(Checklist(listOf(ChecklistItem("c1", "Open Viber", DOING), ChecklistItem("c2", "Reply to Mila", TODO))), first.state.checklist)
        assertEquals(3, first.state.nextId)

        val drafts = listOf(draft("Open Viber", DONE, "c1", step = 2), draft("Reply to Mila", DOING, "c2"), draft("Archive it", TODO))
        val later = saved(ChecklistRules.check(drafts, first.state, setOf(2), step = 3)).state

        assertEquals(listOf("c1", "c2", "c3"), later.checklist.items.map { it.id })
        assertEquals(ChecklistItem("c1", "Open Viber", DONE, step = 2), later.checklist.items.first())
        assertEquals(4, later.nextId)
        assertEquals(mapOf("c1" to 1, "c2" to 1, "c3" to 3), later.added)
    }

    // No step before the one that added an item can have done it; an item that the run started with came in before every step.
    @Test
    fun aDoneItemNeedsAStepAtOrAfterTheStepThatAddedIt() {
        val started = ChecklistState.atStart(Checklist(listOf(ChecklistItem("c1", "Open Viber", TODO))))
        val atFive = saved(ChecklistRules.check(listOf(draft("Open Viber", TODO, "c1"), draft("Reply to Mila", TODO)), started, setOf(3), step = 5)).state
        assertEquals(mapOf("c2" to 5), atFive.added)
        val steps = setOf(3, 5, 7)

        assertEquals(
            "Step 3 cannot mark c2 done: c2 was added at step 5, after it.",
            refusal(ChecklistRules.check(listOf(draft("Open Viber", TODO, "c1"), draft("Reply to Mila", DONE, "c2", step = 3)), atFive, steps, step = 8)),
        )
        val byFive = saved(ChecklistRules.check(listOf(draft("Open Viber", DONE, "c1", step = 3), draft("Reply to Mila", DONE, "c2", step = 5)), atFive, steps, step = 8))
        assertEquals(listOf(3, 5), byFive.state.checklist.items.map { it.step })
        val bySeven = saved(ChecklistRules.check(listOf(draft("Open Viber", TODO, "c1"), draft("Reply to Mila", DONE, "c2", step = 7)), atFive, steps, step = 8))
        assertEquals(7, bySeven.state.checklist.items[1].step)
        // An item that is done stays done, with its step, when a later call sends it without one.
        val kept = saved(ChecklistRules.check(listOf(draft("Reply to Mila", DONE, "c2"), draft("Open Viber", DOING, "c1")), bySeven.state, steps, step = 9))
        assertEquals(ChecklistItem("c2", "Reply to Mila", DONE, step = 7), kept.state.checklist.items[1])
        assertEquals(
            "Step 7 cannot mark c3 done: c3 was added at step 9, after it.",
            refusal(ChecklistRules.check(listOf(draft("Open Viber", TODO, "c1"), draft("Reply to Mila", TODO, "c2"), draft("Archive it", DONE, step = 7)), atFive, steps, step = 9)),
        )
    }

    @Test
    fun aCallHoldsOneToTwentyItemsAndAtMostOneDoing() {
        assertEquals("A call holds 1 to 20 items.", refusal(check(emptyList())))
        assertEquals(
            "A call holds 1 to 20 items; leave out items that are done, skipped or failed.",
            refusal(check(List(21) { draft("Step $it", TODO) })),
        )
        assertEquals("At most one item may be doing.", refusal(check(listOf(draft("A", DOING), draft("B", DOING)))))
    }

    @Test
    fun aDoneItemNeedsTheStepThatDidItUnlessItIsDoneAlready() {
        val current = Checklist(listOf(ChecklistItem("c1", "Open Viber", DONE, step = 2), ChecklistItem("c2", "Reply", DOING)))

        assertEquals("c2 is done, so it needs the step that did it.", refusal(check(listOf(draft("Open Viber", DONE, "c1"), draft("Reply", DONE, "c2")), current, setOf(2, 5), step = 6)))
        assertEquals(
            "Step 4 cannot mark c2 done: name a step that acted on the screen without failing, a data step with a result or a step that the owner did.",
            refusal(check(listOf(draft("Open Viber", DONE, "c1"), draft("Reply", DONE, "c2", step = 4)), current, setOf(2, 5), step = 6)),
        )
        val kept = saved(check(listOf(draft("Open Viber", DONE, "c1"), draft("Reply", DONE, "c2", step = 5)), current, setOf(2, 5), step = 6))
        assertEquals(listOf(2, 5), kept.state.checklist.items.map { it.step })
    }

    @Test
    fun anIdMustBeTheChecklistsOnceAndEveryOpenItemMustBeSent() {
        val current = Checklist(listOf(ChecklistItem("c1", "Open Viber", TODO), ChecklistItem("c2", "Reply", DOING), ChecklistItem("c3", "Archive", TODO)))

        assertEquals("c9 is not in the checklist; leave id out for a new item.", refusal(check(listOf(draft("Open Viber", TODO, "c9")), current)))
        assertEquals("The call names c1 twice.", refusal(check(listOf(draft("Open Viber", TODO, "c1"), draft("Open it", TODO, "c1")), current)))
        assertEquals(
            "The call leaves out c2 and c3, which are still open; send every open item, and mark one that is no longer needed skipped with why.",
            refusal(check(listOf(draft("Open Viber", TODO, "c1")), current)),
        )
    }

    // A long goal is never capped: closed items may be left out and stay, so at most 20 items are open, never 20 in all.
    @Test
    fun aClosedItemMayBeLeftOutOrReopenedSoALongGoalGrowsPastTwentyItems() {
        val replied = (1..19).map { ChecklistItem("c$it", "Reply to chat $it", DONE, step = it) }
        val current = Checklist(replied + ChecklistItem("c20", "Archive", SKIPPED, why = "No archive button") + ChecklistItem("c21", "Reply to Mila", DOING))

        val grown = saved(check(listOf(draft("Reply to Mila", DONE, "c21", step = 30), draft("Reply to Ana", DOING), draft("Archive", TODO, "c20")), current, setOf(30), step = 31))

        assertEquals(
            replied + ChecklistItem("c20", "Archive", TODO) + ChecklistItem("c21", "Reply to Mila", DONE, step = 30) + ChecklistItem("c22", "Reply to Ana", DOING),
            grown.state.checklist.items,
        )
        assertEquals(listOf("c21", "c22", "c20"), grown.sent)
        assertEquals(23, grown.state.nextId)
    }

    @Test
    fun aDoneItemsTextNeverChanges() {
        val current = Checklist(listOf(ChecklistItem("c1", "Open Viber", DONE, step = 2), ChecklistItem("c2", "Reply", TODO)))

        assertEquals("c1 is done, so its text cannot change.", refusal(check(listOf(draft("Open Telegram", DONE, "c1"), draft("Reply", TODO, "c2")), current)))
        assertEquals(ChecklistItem("c2", "Reply to Mila", TODO), saved(check(listOf(draft("Open Viber", DONE, "c1"), draft("Reply to Mila", TODO, "c2")), current)).state.checklist.items[1])
    }

    @Test
    fun aSkippedOrFailedItemNeedsWhyAndOnlyItKeepsOne() {
        assertEquals("c1 is skipped, so it needs why.", refusal(check(listOf(draft("Archive it", SKIPPED)))))
        assertEquals("c1 is failed, so it needs why.", refusal(check(listOf(draft("Archive it", FAILED)))))

        val items = saved(check(listOf(draft("Archive it", FAILED, why = "No archive button"), draft("Reply", TODO, step = 3, why = "Not needed")))).state.checklist.items

        assertEquals(listOf(ChecklistItem("c1", "Archive it", FAILED, why = "No archive button"), ChecklistItem("c2", "Reply", TODO)), items)
    }
}
