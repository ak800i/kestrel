package dev.kestrel.app.conversation

import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The feedback bar's line for a goal (design 10.5 of sub-project 7), for both channels: while the goal acts, its progress,
 * "Step 12 · 3 of 7 · Replying to Mila", from the view's step and checklist; once it is held, [ON_HOLD], which names the
 * words, because the bar takes no touches. A question or a hand-over leaves the bar to the channel that asks it.
 */
internal object GoalBar {
    const val ON_HOLD = "On hold: say 'Kestrel, continue' or open Kestrel."

    /** The bar's line for [view]'s goal, or null when the bar shows nothing of it. */
    fun text(view: ConversationView): String? = when (view.goal) {
        null, GoalPhase.ASKING, GoalPhase.HANDED_OVER -> null
        GoalPhase.ACTING -> progress(view.step, view.checklist)
        GoalPhase.PAUSED, GoalPhase.PAUSED_AT_LIMIT, GoalPhase.INTERRUPTED -> ON_HOLD
    }

    /**
     * Passes each new line of [views] to [show], once; the bar lets a line fade by itself. Null means that the bar has no
     * line for the goal any more (it ended, or it asks), so that a line that still waits for the bar is dropped (review L1).
     */
    suspend fun follow(views: Flow<ConversationView>, show: suspend (String?) -> Unit) {
        views.map(::text).distinctUntilChanged().collect { show(it) }
    }

    private fun progress(step: Int?, checklist: ChecklistSummary?): String? =
        listOfNotNull(step?.let { "Step $it" }, checklist?.let { "${it.done} of ${it.total}" }, checklist?.current)
            .joinToString(" · ")
            .ifEmpty { null }
}
