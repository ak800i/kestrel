package dev.kestrel.agent.loop

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistCheck
import dev.kestrel.agent.goal.ChecklistRules
import dev.kestrel.agent.goal.ChecklistState
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.Tools

/**
 * The run's checklist (design 7.2 of sub-project 7): it starts as the checklist that a resumed goal brings, and each call
 * of `update_checklist` that `ChecklistRules` accepts changes it and is reported to the goal. A call that breaks a rule,
 * and a call right after a saved one with no other tool between them, is an invalid tool call. Its texts are kept in their
 * spoken form, each placeholder as its kind's words, as `say` speaks them. While an item is open, `finish` may not end
 * the run.
 */
internal class ChecklistSteps(private val run: TaskRun, started: Checklist?) {
    // With the checklist, the next new id and the step at which the run added each item; null before the first checklist.
    private var state: ChecklistState? = started?.let(ChecklistState::atStart)

    val checklist: Checklist? get() = state?.checklist
    // Whether the newest step saved the checklist.
    private var savedLast = false

    /** Why `finish` may not end the run yet: the items that are still to do or being done; null when none is. */
    fun finishRefusal(): String? {
        val open = checklist?.open.orEmpty().map { it.id }
        return when (open.size) {
            0 -> null
            1 -> "the checklist has an open item ${open.single()}; mark it done, skipped or failed"
            else -> "the checklist has open items ${ChecklistRules.listed(open)}; mark them done, skipped or failed"
        }
    }

    /** A step of [tool] ran or was refused; any tool but update_checklist ends a row of updates. */
    fun stepped(tool: String) {
        if (tool != Tools.UPDATE_CHECKLIST) savedLast = false
    }

    /** Checks [call], the run's current step, against the rules, with [steps] the steps of this run that an item may be done by. */
    suspend fun update(call: AgentCall.UpdateChecklist, steps: Set<Int>): Done {
        if (savedLast) return refused(call, TWICE)
        val drafts = call.items.map { it.copy(text = run.vault.spoken(it.text), why = it.why?.let(run.vault::spoken)) }
        return when (val check = ChecklistRules.check(drafts, state, steps, run.replies)) {
            is ChecklistCheck.Refused -> refused(call, check.reason)
            is ChecklistCheck.Saved -> {
                state = check.state
                savedLast = true
                val checklist = check.state.checklist
                run.trace.checklist(checklist)
                val saved = "checklist saved, ${checklist.done} of ${checklist.items.size} done (${check.sent.joinToString(", ")})"
                Done(call.tool, text = "Result: $saved.", summary = "Step ${run.replies}: $saved.", last = saved, counted = false)
            }
        }
    }

    private fun refused(call: AgentCall.UpdateChecklist, reason: String): Done {
        val what = "update_checklist failed: ${reason.trimEnd('.')}"
        return Done(call.tool, text = "Result: $what.", summary = "Step ${run.replies}: $what.", last = what, invalid = true)
    }

    override fun toString(): String = "ChecklistSteps(checklist=$checklist, savedLast=$savedLast)"

    private companion object {
        const val TWICE = "it came right after the last update; act on the screen or with another tool before you update the checklist again"
    }
}
