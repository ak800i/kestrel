package dev.kestrel.app.agent

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.Interruption
import dev.kestrel.agent.Ledger
import dev.kestrel.agent.TaskReport
import dev.kestrel.app.providers.ProviderTexts

/**
 * What Kestrel says when a task ends (design D24 of sub-project 5; spec 14.5), built by code from the task's report: a
 * finished task's confirmed sends first, then the model's summary; any other end its reason's sentence and then what the
 * task did, from its ledger ("I opened Viber and typed the message."), or "I didn't change anything.". The model's own
 * text comes already in its spoken form (placeholders as their kinds' words, at most 600 characters, D17 and D24).
 */
internal object AgentTexts {
    const val COULD_NOT_START = "I couldn't start the task."
    const val TEST_BUILD = "this test build runs tasks only with a test script or in a live session."
    const val NO_SERVICE = "Kestrel's accessibility service is off."
    const val NO_PROFILE = "No provider profile is set up. Open Kestrel to add one."
    const val NO_WATCHER = "I can't watch the screen for your touches, so I won't run the task. Turn Kestrel's accessibility " +
        "service off and on."
    const val CALL_STARTED = "I stopped the task because a call started right after my last action."
    const val CANCELLED = "Cancelled."
    const val DONE = "Done."
    const val NOTHING_CHANGED = "I didn't change anything."

    // Kestrel's own memory requests (design D2 of sub-project 5).
    const val REMEMBERED = "I'll remember that."
    const val FORGOTTEN = "I've forgotten it."
    const val NOT_SAVED = "I didn't change anything."
    const val NOTHING_TO_SAVE = "There's nothing to save: no task finished in the last 10 minutes."

    fun noFact(key: String): String = "There's no fact named $key."

    /** "I couldn't start the task." or, with a [reason], "I couldn't start the task: <reason>" (D32 item 2). */
    fun couldNotStart(reason: String? = null): String = if (reason == null) COULD_NOT_START else "I couldn't start the task: $reason"

    /** The sentence that ends task [report]; [provider] names the provider in an error's sentence. */
    fun sentence(report: TaskReport, provider: String): String = when (report.end) {
        EndReason.FINISHED -> (report.sends + (report.summary ?: DONE)).joinToString(" ")
        EndReason.UNVERIFIED ->
            report.summary?.let { "I think it's done, but I couldn't check it: ${it.trimEnd('.')}." } ?: "I think it's done, but I couldn't check it."
        else -> "${reason(report, provider)} ${done(report)}"
    }

    /** "Cancelled: <what the task did>", for the bar when a newer task replaced it (D32 item 4). */
    fun replaced(report: TaskReport): String = "Cancelled: ${ledger(report)}"

    /** What the task did, in Kestrel's words: "I opened Viber, typed the message and sent it." */
    fun ledger(report: TaskReport): String = Ledger.sentence(report.ledger) ?: NOTHING_CHANGED

    // A round's limit says what is done and left from the checklist when the model kept one (plan 7A; design 7.4 of
    // sub-project 7); any other end says what the task did.
    private fun done(report: TaskReport): String =
        Ledger.soFar(report.checklist?.takeIf { report.end in ROUND_LIMITS }, report.ledger) ?: NOTHING_CHANGED

    private val ROUND_LIMITS = setOf(EndReason.STOPPED_AT_LIMIT, EndReason.PAUSED_AT_LIMIT)

    private fun reason(report: TaskReport, provider: String): String = when (report.end) {
        EndReason.FINISHED, EndReason.UNVERIFIED -> DONE
        EndReason.CANCELLED -> CANCELLED
        EndReason.FAILED_BY_MODEL -> report.detail?.let { "I couldn't do it: ${it.trimEnd('.')}." } ?: "I couldn't do it."
        EndReason.DECLINED -> "I stopped because you said no."
        EndReason.HAND_OVER_EXPIRED -> "I stopped because the step that I handed over to you took more than 3 minutes."
        EndReason.STOPPED_AT_LIMIT -> "I stopped at the limit, as you said."
        // Today's runner holds no goal, so a run that paused at a limit has ended; plan 7D says that a goal is held.
        EndReason.PAUSED_AT_LIMIT -> "I stopped at the limit because you didn't answer."
        EndReason.STUCK -> listOfNotNull("I stopped because I wasn't getting anywhere.", report.detail).joinToString(" ")
        EndReason.INVALID_CALLS -> "The model keeps giving answers that I can't use."
        EndReason.PROVIDER -> report.error?.let { ProviderTexts.spoken(it, provider) } ?: "$provider could not use the request."
        EndReason.NO_PROFILE -> NO_PROFILE
        EndReason.NO_SCREEN -> "I can't see the screen: $NO_SERVICE"
        EndReason.AGENT_FREE -> "${report.detail ?: "The app in front"} is on your list of agent-free apps, so I won't act in it."
        EndReason.INTERRUPTED -> when (report.interruption) {
            Interruption.KESTREL_SCREEN -> "I stopped because Kestrel's own screen stayed in front."
            Interruption.TOUCH, null -> "I stopped."
        }
        EndReason.PAUSED_TOO_LONG -> "I stopped the task because it was paused for a long time."
        EndReason.TEST_MODE -> report.detail ?: "Test mode: Kestrel only sends to your own chats."
        EndReason.CONTEXT_TOO_SMALL -> "This model's context is too small for tasks."
        EndReason.MODEL_UNUSABLE -> report.detail ?: "The profile's model can't be used for tasks."
        EndReason.KESTREL_ERROR -> "Something went wrong in Kestrel, so I stopped the task."
    }
}
