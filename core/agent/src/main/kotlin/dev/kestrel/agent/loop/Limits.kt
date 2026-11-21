package dev.kestrel.agent.loop

import dev.kestrel.agent.port.AgentClock
import dev.kestrel.agent.port.ModelInfo
import dev.kestrel.agent.port.RoleModel
import dev.kestrel.providers.http.ProviderError
import dev.kestrel.providers.profiles.ModelRole
import kotlin.math.floor

/**
 * Spec 11.7's limits (design D7): at most [MAX_REPLIES] model replies per task, invalid ones included; at most
 * [MAX_WORK_MILLIS] of Kestrel's own working time, where the time spent waiting for the owner and a paused task do not
 * count; and for a profile whose spending counts credits, a question each time the task has used [CREDIT_STEP] more
 * credits since its start or the owner's last yes.
 */
class Limits(private val clock: AgentClock, private val countsCredits: Boolean) {
    private val started = clock.elapsedMillis()
    private var waited = 0L
    private var allowedCredits = CREDIT_STEP

    var credits = 0.0
        private set

    val workMillis: Long get() = clock.elapsedMillis() - started - waited

    /** Runs [block], a wait for the owner or a pause, whose time does not count as work. */
    suspend fun <T> waiting(block: suspend () -> T): T {
        val start = clock.elapsedMillis()
        try {
            return block()
        } finally {
            waited += clock.elapsedMillis() - start
        }
    }

    fun spent(credits: Double) {
        this.credits += credits
    }

    fun repliesUsed(replies: Int): Boolean = replies >= MAX_REPLIES

    fun timeUsed(): Boolean = workMillis >= MAX_WORK_MILLIS

    /** The cost question's text when the task has reached its allowance of credits, or null (review A N2). */
    fun creditQuestion(): String? =
        if (countsCredits && credits >= allowedCredits) "This task has used ${floor(credits).toLong()} credits. Continue?" else null

    /** The owner said yes to the cost question: 10 more credits. */
    fun allowMoreCredits() {
        allowedCredits = credits + CREDIT_STEP
    }

    override fun toString(): String = "Limits(workMillis=$workMillis, credits=$credits)"

    companion object {
        const val MAX_REPLIES = 30
        const val MAX_WORK_MILLIS = 5 * 60_000L
        const val CREDIT_STEP = 10.0
        const val STUCK_QUESTION = "I'm not getting anywhere. Should I keep trying?"
    }
}

/** What the escalation rules ask the loop to do after a step (design D7). */
enum class Escalate { NOTHING, SWITCHED, ASK_STUCK, END_INVALID, END_STUCK }

/**
 * Spec 11.7's escalation (design D7): every task starts with the lightweight model; after 3 steps in a row without
 * progress or 2 invalid tool calls on it, the task switches to the versatile model and both counts start again. On the
 * versatile model, 3 steps without progress ask the owner whether to keep trying, and 3 invalid tool calls end the task.
 * When both roles name the same model, the switch only resets the counts; when the profile check refused the versatile
 * model ([RoleModel.unusable]), the task does not switch and ends as stuck instead (D32 item 36).
 */
class Escalation(private val model: ModelInfo) {
    var role: ModelRole = ModelRole.LIGHTWEIGHT
        private set
    private var invalid = 0

    val current: RoleModel get() = if (role == ModelRole.LIGHTWEIGHT) model.lightweight else model.versatile

    /** After an invalid tool call (D4), which [progress] does not count. */
    fun invalid(progress: Progress): Escalate {
        invalid++
        return when {
            role == ModelRole.LIGHTWEIGHT && invalid >= LIGHT_INVALID -> switch(progress)
            role == ModelRole.VERSATILE && invalid >= VERSATILE_INVALID -> Escalate.END_INVALID
            else -> Escalate.NOTHING
        }
    }

    /** After a step that [progress] has counted. */
    fun afterStep(progress: Progress): Escalate = when {
        progress.withoutProgress < STUCK_STEPS -> Escalate.NOTHING
        role == ModelRole.LIGHTWEIGHT -> switch(progress)
        else -> Escalate.ASK_STUCK
    }

    /**
     * Whether [error] on the lightweight model moves the task to the versatile model, which repeats the step (spec 14.2:
     * "two retries with backoff, then a switch"); `ProfileClient` has already retried.
     */
    fun switchesFor(error: ProviderError, progress: Progress): Boolean {
        val passing = error == ProviderError.Timeout || error is ProviderError.RateLimited || error is ProviderError.ServerError
        return passing && role == ModelRole.LIGHTWEIGHT && model.versatile.unusable == null &&
            model.versatile.id != model.lightweight.id && switch(progress) == Escalate.SWITCHED
    }

    /** The owner said yes to "Should I keep trying?". */
    fun keepTrying(progress: Progress) {
        progress.reset()
    }

    private fun switch(progress: Progress): Escalate {
        progress.reset()
        invalid = 0
        if (model.versatile.unusable != null) return Escalate.END_STUCK
        role = ModelRole.VERSATILE
        return Escalate.SWITCHED
    }

    override fun toString(): String = "Escalation(role=$role, invalid=$invalid)"

    companion object {
        const val STUCK_STEPS = 3
        const val LIGHT_INVALID = 2
        const val VERSATILE_INVALID = 3
    }
}
