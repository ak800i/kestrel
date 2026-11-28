package dev.kestrel.app.debug

import android.content.ContentResolver
import android.provider.Settings
import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.goal.ButtonActs
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.NoticeGoal
import dev.kestrel.screen.act.PressDispatch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.IdentityHashMap

/**
 * The debug hooks' claims of their own presses of the goal notification's buttons (plan 7G): the debug form of
 * [ButtonActs], which `GoalActionReceiver` asks for the number of each tap as its broadcast arrives. goal-control arms a
 * claim for the button [GoalAction] of the notice of one goal ([NoticeGoal]) before the button's broadcast can come: `tap`
 * at the moment that its press goes to SystemUI ([pressing]), and `send-later` before it hands the button's PendingIntent
 * to AlarmManager, from the alarm's trigger time on ([armForNewProcess]). [take] gives every broadcast the next number
 * among the inputs, and when an armed claim matches the broadcast's button and goal, it uses the claim up and claims that
 * number as the hooks' ([claim], `CommandTags.claim`) before it returns, so before the receiver gives the conversation its
 * input. Any other broadcast, the owner's own tap, gets a plain number.
 *
 * A claim is valid from the moment that its broadcast is due until its own end, on Android's elapsed realtime ([clock]),
 * which every process of one boot shares, and it is used once. Each kind of claim lives as long as its broadcast's
 * delivery may take, and no longer: a press's claim [PRESS_MILLIS] after the press's last hand-over to SystemUI, whose
 * broadcast arrives within milliseconds, and `tap` settles it before it answers ([Press.settle]): it waits for the
 * broadcast until the claim's end and then drops a claim that no broadcast used, so no claim of a press outlives the
 * hook's answer. The broadcast can arrive while the hook waits, because the hooks answer through a provider's call
 * (`TestHookProvider`), which holds none of Kestrel's broadcasts. `send-later`'s claim lives [LATER_MILLIS] after the
 * alarm's trigger time, because its broadcast may first
 * start Kestrel's process. Each claim carries this boot's count ([boot], `Settings.Global.BOOT_COUNT`), because the
 * elapsed realtime starts again at a restart of the phone, so a claim of another boot is dropped. [file] is the record of
 * the armed claims for every process: a claim leaves it when it is used, settled, taken back or expires, so a process that
 * ends before the broadcast, also in a crash, leaves its claims to the next one, and the file is written again in one
 * step (a temporary file, then a rename) at every change. A claim holds the button's name, the goal's key, the boot's
 * count and two times, and no words.
 *
 * Without a boot count ([boot] null: a phone or an Android build that does not keep `Settings.Global.BOOT_COUNT`), no claim
 * in the file can be tied to this boot, so none is trusted: the file is deleted, and the claims live in this process's
 * memory only, where they stay exact, because the process's clock belongs to the current boot. A claim then never reaches
 * a new process, so [armForNewProcess], which `goal-control send-later` uses, refuses by itself, and `tap`, whose broadcast
 * reaches this process, keeps working; when this process ends before the broadcast, the press counts as the owner's.
 *
 * One window remains, and nothing can close it: Kestrel's buttons are immutable PendingIntents (`GoalNotification`), so
 * neither SystemUI's send nor AlarmManager's can add a token, and the receiver cannot tell the hooks' broadcast from the
 * owner's. The owner's own tap on the same button of the same goal's notice, from the moment that the hooks' broadcast is
 * due until it arrives, is taken for the hooks': after a press in the shade, from its first hand-over until the broadcast
 * arrives, or at most until its claim's end, [PRESS_MILLIS] after its last hand-over, when no broadcast came; after the
 * alarm, at most a start of Kestrel's process. The notification lets only the first Continue through (`GoalButtons`), and
 * the hooks' press then made the same choice.
 */
internal class ButtonClaims(
    private val file: File,
    private val claim: (Long) -> Unit,
    private val clock: () -> Long,
    private val boot: Int?,
    private val next: () -> Long = InputIds::next,
) : ButtonActs {
    /**
     * A claim of the hooks' press of [action] on the notice of [goal] in the boot [boot], or in this process only when the
     * phone has no boot count, valid from [fromMillis] until [untilMillis] on [clock].
     */
    data class Claim(val action: GoalAction, val goal: NoticeGoal, val boot: Int?, val fromMillis: Long, val untilMillis: Long)

    private val claims = mutableListOf<Claim>()

    // Each press's signal that its claim was used, by the claim that is armed now; the press waits on it ([Press.settle]).
    private val uses = IdentityHashMap<Claim, CompletableDeferred<Unit>>()

    /** What [armForNewProcess] did: [Armed] its claim, or nothing, because the phone has [NoBootCount]. */
    sealed interface LaterClaim {
        data class Armed(val claim: Claim) : LaterClaim

        data object NoBootCount : LaterClaim
    }

    /**
     * What a press's claim became once the press was over ([Press.settle]), which goal-control's `tap` answers as
     * `claim=<wire>`: [NONE] when the press armed none (no hand-over to SystemUI, or every hand-over refused), [USED] when
     * the button's broadcast used it, and [DROPPED] when no broadcast came before its end, so that the owner's own tap after
     * it is his.
     */
    enum class Settled(val wire: String) { NONE("none"), USED("used"), DROPPED("dropped") }

    init {
        if (boot == null) {
            file.delete()
        } else if (file.isFile) {
            val now = clock()
            val lines = file.readLines()
            lines.mapNotNull(::parse).filterTo(claims) { it.boot == boot && now <= it.untilMillis }
            if (claims.size != lines.size) save(claims)
        }
    }

    /**
     * What goal-control's press of [action]'s button on the notice of [goal] tells the claims (`ScreenActions.tap`'s
     * [PressDispatch]), and how the hook settles its claim afterwards ([Press.settle]).
     */
    fun pressing(action: GoalAction, goal: NoticeGoal): Press = Press(action, goal)

    /**
     * The claim of one press: armed right before the press first goes to SystemUI, once for the whole press, valid until
     * [PRESS_MILLIS] after the press's last hand-over, and kept whatever the press answers, also when it is cancelled or
     * fails later, because from then on the press may have sent the button's broadcast. Only when Android refused every
     * hand-over of the press, as a refused gesture, is the claim taken back, because then nothing reached SystemUI. A press
     * that never goes to SystemUI never arms one.
     */
    inner class Press internal constructor(private val action: GoalAction, private val goal: NoticeGoal) : PressDispatch {
        // The claim that is armed now, and its signal of use, which the press keeps after a broadcast used the claim and
        // forgets with a claim that it took back, so that a later hand-over arms a new one.
        private var armed: Claim? = null
        private var used: CompletableDeferred<Unit>? = null

        // The hand-overs that Android may have taken: a node's action, whatever it answered, or a gesture that it took.
        private var reached = 0

        override fun dispatching() {
            synchronized(this@ButtonClaims) {
                val now = clock()
                val current = armed
                if (used == null) {
                    val claim = arm(action, goal, now, now + PRESS_MILLIS)
                    armed = claim
                    used = CompletableDeferred<Unit>().also { uses[claim] = it }
                } else if (current != null) {
                    armed = renew(current, now + PRESS_MILLIS)
                }
                reached++
            }
        }

        override fun notDispatched() {
            synchronized(this@ButtonClaims) {
                reached--
                if (reached == 0) {
                    armed?.let { disarm(it) }
                    armed = null
                    used = null
                }
            }
        }

        /**
         * Settles the press's claim once the press is over, before the hook answers: waits until a broadcast used the
         * claim, at most until the claim's end, and then drops a claim that none used, so that no claim of this press
         * outlives the hook's answer (the whole-plan review's I1).
         */
        suspend fun settle(): Settled {
            val (claim, signal) = synchronized(this@ButtonClaims) { armed to used }
            if (signal == null) return Settled.NONE
            // A claim that a broadcast used is armed no more; one that is still armed waits for its broadcast until its end.
            if (claim != null) withTimeoutOrNull((claim.untilMillis - clock()).coerceAtLeast(0)) { signal.await() }
            return synchronized(this@ButtonClaims) {
                if (signal.isCompleted) {
                    Settled.USED
                } else {
                    claim?.let { disarm(it) }
                    armed = null
                    Settled.DROPPED
                }
            }
        }
    }

    // Arms a claim of the hooks' press of [action] on the notice of [goal], valid from [fromMillis] until [untilMillis].
    @Synchronized
    private fun arm(action: GoalAction, goal: NoticeGoal, fromMillis: Long, untilMillis: Long): Claim {
        val armed = Claim(action, goal, boot, fromMillis, untilMillis)
        change(claims + armed)
        return armed
    }

    // [armed] valid until [untilMillis], or null when it is armed no more, because a broadcast used it.
    @Synchronized
    private fun renew(armed: Claim, untilMillis: Long): Claim? {
        if (claims.none { it === armed }) return null
        val renewed = armed.copy(untilMillis = maxOf(armed.untilMillis, untilMillis))
        change(claims.map { if (it === armed) renewed else it })
        uses.remove(armed)?.let { uses[renewed] = it }
        return renewed
    }

    /**
     * Arms a claim whose broadcast may reach a new process of this boot, as `send-later`'s alarm does, valid from
     * [fromMillis], the alarm's trigger time, for [LATER_MILLIS]: only the file carries it there, so without a boot count it
     * arms nothing and answers [LaterClaim.NoBootCount].
     */
    @Synchronized
    fun armForNewProcess(action: GoalAction, goal: NoticeGoal, fromMillis: Long): LaterClaim =
        if (boot == null) LaterClaim.NoBootCount else LaterClaim.Armed(arm(action, goal, fromMillis, fromMillis + LATER_MILLIS))

    /** Takes back [armed], whose broadcast was certainly never sent: a refused gesture, or an alarm that AlarmManager did not take. */
    @Synchronized
    fun disarm(armed: Claim) {
        uses.remove(armed)
        change(claims.filterNot { it === armed })
    }

    @Synchronized
    override fun take(action: GoalAction?, goal: NoticeGoal?): Long {
        val act = next()
        val now = clock()
        val used = claims.filter { it.action == action && it.goal == goal && now in it.fromMillis..it.untilMillis }.minByOrNull { it.fromMillis }
        change(claims.filter { it !== used && now <= it.untilMillis })
        if (used != null) {
            claim(act)
            uses.remove(used)?.complete(Unit)
        }
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
        if (boot == null) return
        if (saved.isEmpty()) {
            file.delete()
            return
        }
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(saved.joinToString("\n") { "${it.action.name}\t${it.goal.key}\t${it.boot}\t${it.fromMillis}\t${it.untilMillis}" })
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    // A line that is no claim is none.
    private fun parse(line: String): Claim? {
        val parts = line.trim().split('\t').takeIf { it.size == 5 } ?: return null
        val action = GoalAction.entries.firstOrNull { it.name == parts[0] } ?: return null
        val goal = NoticeGoal.ofKey(parts[1]) ?: return null
        val boot = parts[2].toIntOrNull() ?: return null
        val from = parts[3].toLongOrNull() ?: return null
        val until = parts[4].toLongOrNull() ?: return null
        return Claim(action, goal, boot, from, until)
    }

    override fun toString(): String = "ButtonClaims(${claims.size})"

    companion object {
        /** The armed claims' file in Kestrel's `noBackupFilesDir`. */
        const val FILE = "hook-button-claims.txt"

        /**
         * How long a press's claim lives after the press's last hand-over to SystemUI: SystemUI sends the button's
         * broadcast within milliseconds of a press, so 3 s leaves ample room on a loaded phone, and `tap` drops the claim
         * at its end when no broadcast used it ([Press.settle]).
         */
        const val PRESS_MILLIS = 3_000L

        /**
         * How long `send-later`'s claim lives after the alarm's trigger time: longer than the broadcast's delivery to a
         * process that the alarm starts, so that only a broadcast that never comes leaves the claim to expire.
         */
        const val LATER_MILLIS = 30_000L

        @Volatile
        private var current: ButtonClaims? = null

        /**
         * This boot's count, which Android counts up at every boot, or null when the phone does not keep it or keeps no
         * number. It never throws, so that a phone without it starts Kestrel as usual; its claims then stay in one process.
         */
        fun bootCount(resolver: ContentResolver): Int? =
            Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }

        /**
         * Starts the claims of this process in the boot [boot], or in this process only without a boot count
         * (`DebugKestrelApplication`, once, before any broadcast): reads the claims that an earlier process of this boot
         * armed in [dir], has [tags] claim the number of each used claim, and gives [install] the receiver's seam,
         * `AppGraph.buttonActs`.
         */
        @Synchronized
        fun start(dir: File, tags: CommandTags, clock: () -> Long, boot: Int?, install: (ButtonActs) -> Unit) {
            val claims = ButtonClaims(File(dir, FILE), tags::claim, clock, boot)
            current = claims
            install(claims)
        }

        /** The claims of this process, which goal-control arms. */
        fun current(): ButtonClaims = checkNotNull(current) { "the button claims start with Kestrel's debug application" }
    }
}