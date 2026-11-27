package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.run.ConversationGuard

/**
 * The end of the quiet time between two turns (design 6.6 and 9.6 of sub-project 7): the open conversation closes as at
 * the end of its quiet time, so that the next command opens a new one ([ConversationGuard.close]).
 */
data object EndQuietTime : TurnAction {
    override fun run(scope: RunScope) = ConversationGuard(scope.hooks, scope.clock).close()

    override fun toString() = "the end of the quiet time"
}

/**
 * The owner's touch while Kestrel's goal acts (design 7.2 and D13 of sub-project 7), which interrupts the goal: the hook
 * goal-control do=touch (plan 7E) gives the conversation the touch that the touch watcher reports, because a tap on the
 * screen would press what lies under it in the app. A lost answer is read back by the turn that follows, which awaits the
 * interrupted end.
 */
data object OwnerTouch : TurnAction {
    override fun run(scope: RunScope) {
        try {
            scope.hooks.goalControl("touch")
        } catch (e: HookLost) {
            // The goal's end shows whether the touch arrived.
        }
    }

    override fun toString() = "the owner's touch"
}

/**
 * A round's limits at the Conversations page's smallest for the run (design 7.4 and 15.3 of sub-project 7:
 * limit-continue): 50 replies, 5 minutes and 10 AI Credits, set with conversation-control do=limits, which answers the
 * limits that it found as `was=`. The restore, pushed before the change, puts those back and checks them; when the
 * change's answer was lost, they are not known, and the restore fails with what to do by hand.
 */
data object SmallestLimits : Setup {
    private const val BY_HAND = "Kestrel > Settings > Conversations: set a round's limits back to the owner's."

    override fun apply(scope: RunScope): SetupResult {
        var was: String? = null
        scope.restores.push("a round's limits", BY_HAND) {
            val stored = checkNotNull(was) { "The limits before the run are not known, because conversation-control's answer was lost." }
            val answer = scope.hooks.conversationControl("limits", stored)
            check(answer["limits"] == stored) { "A round's limits are ${answer["limits"]}, not $stored again." }
        }
        was = scope.restores.change("a round's limits at the smallest", BY_HAND) { scope.hooks.conversationControl("limits") }["was"]
        return SetupResult.Ready
    }
}

/** conversation-state (design 14.4 of sub-project 7) shows the open conversation as each field that is not null says. */
data class ConversationIs(val open: Boolean? = null, val continued: Boolean? = null, val commands: Int? = null, val goal: String? = null) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val state = scope.hooks.conversationState()
        return (open == null || state["open"] == yesNo(open)) && (continued == null || state["continued"] == yesNo(continued)) &&
            (commands == null || state["commands"] == "$commands") && (goal == null || state["goal"] == goal)
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"
}

/** agent-state shows the newest command's goal in [phase], such as `paused-at-limit` or `interrupted`. */
data class PhaseIs(val phase: String) : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val tag = scope.tag ?: return false
        return scope.hooks.agentState(tag)["phase"] == phase
    }
}

/** agent-state shows the newest command's goal with [done] of its [total] checklist items done (design 7.5 of sub-project 7). */
data class ChecklistIs(val done: Int, val total: Int) : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val tag = scope.tag ?: return false
        return scope.hooks.agentState(tag)["checklist"] == "$done/$total"
    }
}

/** The restore at the start of Kestrel's process found [restored], such as `goal-held` (design 6.7 of sub-project 7). */
data class RestoredAs(val restored: String) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = scope.hooks.restoreCheck()["restored"] == restored
}

/**
 * The run's answers and the questions that it left open hold [entries] in their order, each as `<kind>:<answer>` (with
 * `none` for a question left open), such as `limit:yes` before `limit:none`; other entries may stand between them.
 */
data class AskedInOrder(val entries: List<String>) : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val asked = scope.asked.map { it.toString() }.iterator()
        return entries.all { entry -> asked.asSequence().any { it == entry } }
    }
}
