package dev.kestrel.app.debug

import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.goal.ButtonActs
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.NoticeGoal
import java.io.File

/**
 * The debug hooks' claims of their own presses of the goal notification's buttons (plan 7G): the debug form of
 * [ButtonActs], which `GoalActionReceiver` asks for the number of each tap as its broadcast arrives. goal-control arms a
 * claim for the button [GoalAction] of the notice of one goal ([NoticeGoal]) before its press: `tap` before it presses the
 * button in the shade ([arm]), and `send-later` before it hands the button's PendingIntent to AlarmManager ([armSaved]).
 * [take] gives every broadcast the next number among the inputs, and when an armed claim matches the broadcast's button
 * and goal, it uses the claim up and claims that number as the hooks' ([claim], `CommandTags.claim`) before it returns,
 * so before the receiver gives the conversation its input. Any other broadcast, the owner's own tap, gets a plain number.
 *
 * A claim is used once, and it expires [LIFE_MILLIS] after the moment that its broadcast is due ([clock], Android's
 * elapsed realtime, which every process of one boot shares). The alarm of `send-later` may reach a new process, so its
 * claim is also saved in [file], which the next process reads once at its start and then deletes; a claim that is used or
 * expires there is not saved again. A claim holds the button's name and the goal's key, which holds no words.
 *
 * One window remains: the owner's own tap on the same button of the same goal's notice, while a hook's press is armed and
 * before its broadcast arrives, is taken for the hooks'. The notification lets only the first Continue through
 * (`GoalButtons`), and the hooks' press then made the same choice.
 */
internal class ButtonClaims(
    private val file: File,
    private val claim: (Long) -> Unit,
    private val clock: () -> Long,
    private val next: () -> Long = InputIds::next,
) : ButtonActs {
    /** A claim of the hooks' press of [action] on the notice of [goal], until [untilMillis] on [clock]. */
    data class Claim(val action: GoalAction, val goal: NoticeGoal, val untilMillis: Long, val saved: Boolean)

    private val claims = mutableListOf<Claim>()

    init {
        if (file.isFile) {
            val now = clock()
            // Read once: a claim of an earlier process lives on in this one's memory only. One that lies further ahead than
            // any claim can, as after a restart of the phone, is no claim.
            file.readLines().mapNotNull(::parse).filter { it.untilMillis in now..now + MAX_AHEAD_MILLIS }.mapTo(claims) { it.copy(saved = false) }
            file.delete()
        }
    }

    /** Arms a claim of the hooks' press of [action] on the notice of [goal] in this process, due now. */
    @Synchronized
    fun arm(action: GoalAction, goal: NoticeGoal): Claim = Claim(action, goal, clock() + LIFE_MILLIS, saved = false).also { claims += it }

    /**
     * Arms a claim of the hooks' press of [action] on the notice of [goal] whose broadcast is due [dueMillis] from now, and
     * may reach a new process: the claim is saved in [file] as well.
     */
    @Synchronized
    fun armSaved(action: GoalAction, goal: NoticeGoal, dueMillis: Long): Claim =
        Claim(action, goal, clock() + dueMillis + LIFE_MILLIS, saved = true).also {
            claims += it
            save()
        }

    /** Takes back [armed], whose press never happened. */
    @Synchronized
    fun disarm(armed: Claim) {
        if (claims.remove(armed) && armed.saved) save()
    }

    @Synchronized
    override fun take(action: GoalAction?, goal: NoticeGoal?): Long {
        val act = next()
        val now = clock()
        val expired = claims.filter { it.untilMillis < now }
        claims -= expired.toSet()
        val used = claims.firstOrNull { it.action == action && it.goal == goal }
        if (used != null) {
            claims -= used
            claim(act)
        }
        if ((expired + listOfNotNull(used)).any { it.saved }) save()
        return act
    }

    private fun save() {
        val saved = claims.filter { it.saved }
        if (saved.isEmpty()) {
            file.delete()
        } else {
            file.writeText(saved.joinToString("\n") { "${it.action.name}\t${it.goal.key}\t${it.untilMillis}" })
        }
    }

    // A line that is no claim is none.
    private fun parse(line: String): Claim? {
        val parts = line.trim().split('\t').takeIf { it.size == 3 } ?: return null
        val action = GoalAction.entries.firstOrNull { it.name == parts[0] } ?: return null
        val goal = NoticeGoal.ofKey(parts[1]) ?: return null
        val until = parts[2].toLongOrNull() ?: return null
        return Claim(action, goal, until, saved = true)
    }

    override fun toString(): String = "ButtonClaims(${claims.size})"

    companion object {
        /** The saved claims' file in Kestrel's `noBackupFilesDir`. */
        const val FILE = "hook-button-claims.txt"

        /** How long after its broadcast is due a claim lives. */
        const val LIFE_MILLIS = 30_000L

        // No claim lies further ahead than a send-later's alarm and a claim's life.
        private const val MAX_AHEAD_MILLIS = 5 * 60_000L

        @Volatile
        private var current: ButtonClaims? = null

        /**
         * Starts the claims of this process (`DebugKestrelApplication`, once, before any broadcast): reads the claims that
         * an earlier process saved in [dir], has [tags] claim the number of each used claim, and gives [install] the
         * receiver's seam, `AppGraph.buttonActs`.
         */
        @Synchronized
        fun start(dir: File, tags: CommandTags, clock: () -> Long, install: (ButtonActs) -> Unit) {
            val claims = ButtonClaims(File(dir, FILE), tags::claim, clock)
            current = claims
            install(claims)
        }

        /** The claims of this process, which goal-control arms. */
        fun current(): ButtonClaims = checkNotNull(current) { "the button claims start with Kestrel's debug application" }
    }
}
