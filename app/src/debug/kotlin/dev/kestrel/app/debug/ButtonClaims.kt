package dev.kestrel.app.debug

import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.goal.ButtonActs
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.NoticeGoal
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The debug hooks' claims of their own presses of the goal notification's buttons (plan 7G): the debug form of
 * [ButtonActs], which `GoalActionReceiver` asks for the number of each tap as its broadcast arrives. goal-control arms a
 * claim for the button [GoalAction] of the notice of one goal ([NoticeGoal]) before the button's broadcast can come: `tap`
 * at the moment that its press goes to SystemUI ([pressing]), and `send-later` before it hands the button's PendingIntent
 * to AlarmManager, from the alarm's trigger time on ([arm]). [take] gives every broadcast the next number among the inputs,
 * and when an armed claim matches the broadcast's button and goal, it uses the claim up and claims that number as the
 * hooks' ([claim], `CommandTags.claim`) before it returns, so before the receiver gives the conversation its input. Any
 * other broadcast, the owner's own tap, gets a plain number.
 *
 * A claim is valid from the moment that its broadcast is due, the press or the alarm's trigger time, until [LIFE_MILLIS]
 * later, on Android's elapsed realtime ([clock]), which every process of one boot shares; it is used once. Each claim
 * carries this boot's count ([boot], `Settings.Global.BOOT_COUNT`), because the elapsed realtime starts again at a
 * restart of the phone, so a claim of another boot is dropped. [file] is the record of the armed claims for every process:
 * a claim leaves it only when it is used or expires, so a process that ends before the broadcast, also in a crash, leaves
 * its claims to the next one, and the file is written again in one step (a temporary file, then a rename) at every
 * change. A claim holds the button's name, the goal's key, the boot's count and a time, and no words.
 *
 * One window remains, and nothing can close it: Kestrel's buttons are immutable PendingIntents (`GoalNotification`), so
 * neither SystemUI's send nor AlarmManager's can add a token, and the receiver cannot tell the hooks' broadcast from the
 * owner's. The owner's own tap on the same button of the same goal's notice, from the moment that the hooks' broadcast is
 * due until it arrives (a fraction of a second after a press in the shade; at most a start of Kestrel's process after the
 * alarm), is taken for the hooks'. The notification lets only the first Continue through (`GoalButtons`), and the hooks'
 * press then made the same choice.
 */
internal class ButtonClaims(
    private val file: File,
    private val claim: (Long) -> Unit,
    private val clock: () -> Long,
    private val boot: Int,
    private val next: () -> Long = InputIds::next,
) : ButtonActs {
    /** A claim of the hooks' press of [action] on the notice of [goal] in the boot [boot], valid from [fromMillis] on [clock]. */
    data class Claim(val action: GoalAction, val goal: NoticeGoal, val boot: Int, val fromMillis: Long) {
        /** The end of the claim's life on [clock]. */
        val untilMillis: Long get() = fromMillis + LIFE_MILLIS
    }

    private val claims = mutableListOf<Claim>()

    init {
        if (file.isFile) {
            val now = clock()
            val lines = file.readLines()
            lines.mapNotNull(::parse).filterTo(claims) { it.boot == boot && now <= it.untilMillis }
            if (claims.size != lines.size) save(claims)
        }
    }

    /**
     * The step that goal-control's press of [action]'s button on the notice of [goal] runs right before the press goes to
     * SystemUI (`ScreenActions.tap`'s `dispatching`): it arms the press's claim from that moment, once. Nothing takes it
     * back, because from then on the press may have sent the button's broadcast, whatever the press answers, also when it
     * is cancelled or fails later; an unused claim expires. A press that never goes to SystemUI never arms one.
     */
    fun pressing(action: GoalAction, goal: NoticeGoal): () -> Unit {
        val armed = AtomicBoolean(false)
        return { if (armed.compareAndSet(false, true)) arm(action, goal) }
    }

    /** Arms a claim of the hooks' press of [action] on the notice of [goal], valid from [fromMillis] on [clock], now by default. */
    @Synchronized
    fun arm(action: GoalAction, goal: NoticeGoal, fromMillis: Long = clock()): Claim {
        val armed = Claim(action, goal, boot, fromMillis)
        change(claims + armed)
        return armed
    }

    /** Takes back [armed], whose broadcast was certainly never sent, as an alarm that AlarmManager did not take. */
    @Synchronized
    fun disarm(armed: Claim) {
        change(claims.filterNot { it === armed })
    }

    @Synchronized
    override fun take(action: GoalAction?, goal: NoticeGoal?): Long {
        val act = next()
        val now = clock()
        val used = claims.filter { it.action == action && it.goal == goal && now in it.fromMillis..it.untilMillis }.minByOrNull { it.fromMillis }
        change(claims.filter { it !== used && now <= it.untilMillis })
        if (used != null) claim(act)
        return act
    }

    // The file first, so that the claims in memory are always the ones that the file holds.
    private fun change(left: List<Claim>) {
        if (left == claims) return
        save(left)
        claims.clear()
        claims += left
    }

    private fun save(saved: List<Claim>) {
        if (saved.isEmpty()) {
            file.delete()
            return
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(saved.joinToString("\n") { "${it.action.name}\t${it.goal.key}\t${it.boot}\t${it.fromMillis}" })
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // A line that is no claim is none.
    private fun parse(line: String): Claim? {
        val parts = line.trim().split('\t').takeIf { it.size == 4 } ?: return null
        val action = GoalAction.entries.firstOrNull { it.name == parts[0] } ?: return null
        val goal = NoticeGoal.ofKey(parts[1]) ?: return null
        val boot = parts[2].toIntOrNull() ?: return null
        val from = parts[3].toLongOrNull() ?: return null
        return Claim(action, goal, boot, from)
    }

    override fun toString(): String = "ButtonClaims(${claims.size})"

    companion object {
        /** The armed claims' file in Kestrel's `noBackupFilesDir`. */
        const val FILE = "hook-button-claims.txt"

        /**
         * How long after its broadcast is due a claim lives: longer than the broadcast's delivery, also to a process that
         * the alarm starts, so that only a broadcast that never comes leaves a claim to expire.
         */
        const val LIFE_MILLIS = 30_000L

        @Volatile
        private var current: ButtonClaims? = null

        /**
         * Starts the claims of this process in the boot [boot] (`DebugKestrelApplication`, once, before any broadcast):
         * reads the claims that an earlier process armed in [dir], has [tags] claim the number of each used claim, and gives
         * [install] the receiver's seam, `AppGraph.buttonActs`.
         */
        @Synchronized
        fun start(dir: File, tags: CommandTags, clock: () -> Long, boot: Int, install: (ButtonActs) -> Unit) {
            val claims = ButtonClaims(File(dir, FILE), tags::claim, clock, boot)
            current = claims
            install(claims)
        }

        /** The claims of this process, which goal-control arms. */
        fun current(): ButtonClaims = checkNotNull(current) { "the button claims start with Kestrel's debug application" }
    }
}