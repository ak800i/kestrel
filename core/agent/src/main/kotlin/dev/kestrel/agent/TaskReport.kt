package dev.kestrel.agent

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.memory.Recording
import dev.kestrel.providers.http.ProviderError
import kotlinx.coroutines.CancellationException

/** Why a task ended (design D7, D13, D16, D18, D21 and D24). `:app`'s `AgentTexts` turns it into the spoken sentence. */
enum class EndReason {
    FINISHED,
    UNVERIFIED,
    FAILED_BY_MODEL,
    CANCELLED,
    DECLINED,
    HAND_OVER_EXPIRED,
    STEPS,
    TIME,
    COST,
    STUCK,
    INVALID_CALLS,
    PROVIDER,
    NO_PROFILE,
    NO_SCREEN,
    AGENT_FREE,
    PAUSED_TOO_LONG,

    /** Test mode blocked a send, a call or `compose_sms` (D18). */
    TEST_MODE,

    /** A local Ollama model cannot hold the fixed part and two observations (D32 item 10). */
    CONTEXT_TOO_SMALL,

    /** The profile check refused the model that the task needs (D32 items 36 and 37); [TaskReport.detail] says why. */
    MODEL_UNUSABLE,

    /** An unexpected failure inside the agent; it is reported through `graph.errors` by its class (D32 item 48). */
    KESTREL_ERROR,
}

/**
 * How `:app` cancels a task's job with a reason (design D2): a touch, "stop" or a new task after "stop" give
 * [EndReason.CANCELLED], and the end of a 30-minute pause gives [EndReason.PAUSED_TOO_LONG]. Any other
 * CancellationException counts as [EndReason.CANCELLED].
 */
class TaskCancellation(val reason: EndReason) : CancellationException("The task was cancelled: $reason")

/**
 * The end of one task (design D24 and D28), which `Agent.run` hands to its caller once on every path. [summary] is the
 * model's `finish` summary or the task's last words, already in their spoken form (placeholders as their kind's words, at
 * most 600 characters); it is null after a declined confirmation. [detail] is Kestrel's own part of the sentence: the
 * agent-free app's label, a block's reason, the model's `fail` reason or the profile check's sentence. [sends] are
 * Kestrel's lines for the confirmed sends ("Sent to My Notes on Viber."), [recipients] the confirmed recipients, and the
 * counts are those that the debug hook `agent-task -Result` reports (D28). A `FINISHED` task that typed no vault value
 * carries its [recording], which `:app` keeps for 10 minutes for "Kestrel, save that as <name>" (D23); [offerRoutine] is
 * true after the second `FINISHED` run with the same `finish` signature, and then `:app` asks `RoutineMaker.offer` after
 * it has spoken the report (never for a tagged test task). [checklist] is the run's checklist at its end, which the
 * report of a round's limit names (plan 7A; design 7.4 of sub-project 7). toString prints only kinds and counts (D25).
 */
data class TaskReport(
    val end: EndReason,
    val detail: String? = null,
    val summary: String? = null,
    val error: ProviderError? = null,
    val ledger: List<String> = emptyList(),
    val sends: List<String> = emptyList(),
    val recipients: List<String> = emptyList(),
    val steps: Int = 0,
    val workMillis: Long = 0,
    val credits: Double = 0.0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val confirmed: Int = 0,
    val unconfirmed: Int = 0,
    val beforeTestMode: Int = 0,
    val shareItems: Int = 0,
    val modelId: String? = null,
    val recording: Recording? = null,
    val offerRoutine: Boolean = false,
    val checklist: Checklist? = null,
) {
    override fun toString(): String =
        "TaskReport(end=$end, error=$error, detail=${detail?.length}, summary=${summary?.length}, ledger=${ledger.size}, " +
            "sends=${sends.size}, recipients=${recipients.size}, steps=$steps, workMillis=$workMillis, credits=$credits, " +
            "tokens=${inputTokens + outputTokens}, cacheRead=$cacheRead, confirmed=$confirmed, unconfirmed=$unconfirmed, " +
            "beforeTestMode=$beforeTestMode, shareItems=$shareItems, recording=${recording != null}, offerRoutine=$offerRoutine, " +
            "checklist=$checklist)"
}
