package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.device.Deadline
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.run.ConversationGuard
import dev.kestrel.eval.run.unlessLost
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The end of the quiet time between two turns (design 6.6 and 9.6 of sub-project 7): the open conversation closes as at
 * the end of its quiet time, so that the next command opens a new one ([ConversationGuard.close]).
 */
data object EndQuietTime : TurnAction {
    override fun run(scope: RunScope) = scope.conversations.close()

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
 * limit-continue): 50 replies, 5 minutes and 10 AI Credits. Only Kestrel's own answers decide what the restore does:
 * before the change, the step reads the limits that Kestrel stores (conversation-control do=limits-check), and the
 * restore, pushed before that read, reads them again. When they are still those of before, nothing changed, as after a
 * Ctrl+C before the change or a refused change, and the restore does nothing; else it sets them back and reads them back
 * to confirm. A set of given limits does the same when it is sent twice, so it goes through the retried path
 * ([dev.kestrel.eval.hook.Hooks.conversationLimits]), the change and the restore's alike.
 */
data object SmallestLimits : Setup {
    private const val BY_HAND = "Kestrel > Settings > Conversations: set a round's limits back to the owner's."

    override fun apply(scope: RunScope): SetupResult {
        // The limits before the change, as Kestrel answered them; the shutdown hook's thread reads them too.
        val before = AtomicReference<String?>(null)
        scope.restores.push("a round's limits", BY_HAND) {
            // Without the read, the change has not been sent either.
            val was = before.get() ?: return@push
            if (limits(scope.hooks) == was) return@push
            scope.hooks.conversationLimits(was)
            check(limits(scope.hooks) == was) { "A round's limits are not those of before the run again." }
        }
        before.set(limits(scope.hooks))
        scope.restores.change("a round's limits at the smallest", BY_HAND) { scope.hooks.conversationLimits() }
        return SetupResult.Ready
    }

    private fun limits(hooks: Hooks): String =
        checkNotNull(hooks.limitsCheck()["limits"]) { "Kestrel answered conversation-control do=limits-check without its limits." }
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

/**
 * Waits until Kestrel's guard against a crash loop (main spec 8.5) has passed, before a task that ends Kestrel's process
 * ([Turn.Restart]): a crash less than the guard after a restore turned Kestrel on keeps it off, and the previous run's
 * restart was such a restore. voice-restore gives the restore's age; the step waits until it is older than the guard and
 * [MARGIN] more, for at most [WAIT] by the clock, and a lost answer is asked again after [LOOK]. Every read goes through
 * the wait's [Deadline], so none outlives it, and the step fails as soon as its next pause would not end before it.
 */
data object CrashGuardPassed : Setup {
    val MARGIN = 5.seconds
    val WAIT = 180.seconds
    private val LOOK = 10.seconds

    override fun apply(scope: RunScope): SetupResult {
        val deadline = Deadline(scope.clock, WAIT)
        while (true) {
            val answer = unlessLost { scope.hooks.until(deadline).voiceRestore() }
            val wait = if (answer == null) LOOK else left(answer, MARGIN) ?: return SetupResult.Ready
            if (wait >= deadline.left) {
                throw SetupFailed("Kestrel's guard against a crash loop did not pass within ${WAIT.inWholeSeconds} s (voice-restore: ${answer?.text ?: "no answer"}).")
            }
            scope.clock.sleep(wait)
        }
    }

    /**
     * How long Kestrel's guard holds after voice-restore's [answer], with [margin] more, or null when it has passed or no
     * restore's time is stored.
     */
    fun left(answer: HookAnswer, margin: Duration): Duration? {
        val restored = answer["restoredAgo"]?.takeIf { it != "none" } ?: return null
        val ago = checkNotNull(restored.toLongOrNull()) { "voice-restore gave no number of seconds in restoredAgo=." }
        val guard = checkNotNull(answer["guard"]?.toLongOrNull()) { "voice-restore gave no number of seconds in guard=." }
        val left = guard.seconds + margin - ago.seconds
        return left.takeIf { it.isPositive() }
    }
}

/**
 * The run's end is not [end], such as `FAILED_BY_MODEL`, which the scripted model's `fail` call gives when a request
 * lacks a text that its reply requires (Task 5); a dry run's check ([DryRun.checks]).
 */
data class EndedOtherThan(val end: String) : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = answer["end"].let { it != null && it != end }
}
