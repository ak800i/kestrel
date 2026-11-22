package dev.kestrel.screen.act

import android.view.KeyCharacterMap
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.serialization.Serializable

/**
 * How the touch watcher tells an echo of Kestrel's own action from the owner's finger on a device (design D19 of
 * sub-project 5). Under every rule, a touch of the virtual input device while one of Kestrel's gestures runs, or within
 * 100 ms after it, is Kestrel's.
 */
@Serializable
enum class TouchRule {
    /** Nothing else is Kestrel's: Android 13's default, where no click of Kestrel's is known to reach the watcher. */
    NONE,

    /**
     * Also every touch that carries `MotionEvent`'s accessibility flag, from Android 17 on; on Android 13 to 16 a real
     * touch during a gesture carries it too (B35), so there this rule works as [CREDITS].
     */
    FIELD,

    /** Also one touch per click or long click of Kestrel's, accepted or refused, within [OwnActions.CREDIT_MILLIS] after it. */
    CREDITS,
}

/**
 * Whether the touch watcher sees the owner's finger on a device ([A]), or whether Kestrel also needs spec 14.1's fallback
 * of the screen events that it did not cause ([B]). The A71's first sitting chooses it (design D19 of sub-project 5).
 */
@Serializable
enum class TouchBranch { A, B }

/** A device's rule and branch, which the debug hook `agent-touch-source` stores per device. */
@Serializable
data class TouchSource(val rule: TouchRule, val branch: TouchBranch = TouchBranch.A) {
    companion object {
        /** [TouchRule.NONE] on Android 13, and [TouchRule.CREDITS] above it until a probe there says otherwise. */
        fun defaultFor(sdk: Int): TouchSource = TouchSource(if (sdk <= 33) TouchRule.NONE else TouchRule.CREDITS)
    }
}

/**
 * One touch that the watcher saw, as the rule classified it: whether it is the [owner]'s, its input [deviceId] (or
 * [OwnActions.SIGNAL_DEVICE] for a signal without a touch), [source], [toolType] and [flags], the time since Kestrel's last
 * node action returned ([sinceNodeActionMillis], null when none ran while the watcher ran), and whether a credit took it.
 * It holds no screen content.
 */
data class Touch(
    val owner: Boolean,
    val deviceId: Int,
    val source: Int,
    val toolType: Int,
    val flags: Int,
    val sinceNodeActionMillis: Long?,
    val credited: Boolean,
)

/**
 * Kestrel's own actions while the touch watcher runs (design D19 of sub-project 5): `ScreenActions` reports each node
 * action, gesture and settle wait here, and the watcher asks [classify] about each touch. With [TouchRule.CREDITS], each
 * click or long click ([ECHOING]) while the watcher runs creates one credit, which lives from the call until
 * [CREDIT_MILLIS] after it returned; the first touch inside that time uses it and is Kestrel's. A refused click keeps its
 * credit like an accepted one, because Android 17 echoes the click before the app answers, and the credit covers that
 * echo. Starting the watcher and [clear] forget every credit, so that an action made before can never hide the owner's
 * touch. [busy] tells `OwnerSignal` that Kestrel is acting or settling. Any thread may call it.
 */
class OwnActions internal constructor(private val clock: () -> Long, private val sdk: Int) {
    enum class Kind { CLICK, LONG_CLICK, SCROLL, SET_TEXT, FOCUS, OTHER }

    @Volatile
    var source: TouchSource = TouchSource.defaultFor(sdk)

    /** Where a touch of the owner's that no input event carries goes: the watcher's report. */
    @Volatile
    internal var report: (Touch) -> Unit = {}

    private class Credit {
        var until = Long.MAX_VALUE
        var used = false
    }

    private val lock = Any()
    private val credits = mutableListOf<Credit>()
    private var acting = 0
    private var quietFrom = Long.MIN_VALUE
    private var lastNodeAction: Long? = null
    private var watching = false

    /** The watcher started (true) or stopped; either forgets every credit. */
    internal fun watching(on: Boolean) = synchronized(lock) {
        watching = on
        credits.clear()
        if (!on) lastNodeAction = null
    }

    /** Forgets every credit, as when a card shows or a question is asked (design D19 rule 3). */
    fun clear() = synchronized(lock) { credits.clear() }

    /**
     * Runs [perform], a node action of [kind], as Kestrel's own, and returns its answer. A click's credit stays whether the
     * app accepts or refuses it, because Android 17 echoes the click before the app answers; only a call that throws
     * takes back its unused credit.
     */
    internal fun node(kind: Kind, perform: () -> Boolean): Boolean {
        val credit = synchronized(lock) {
            acting++
            if (watching && kind in ECHOING && credits()) Credit().also { credits += it } else null
        }
        var returned = false
        try {
            return perform().also { returned = true }
        } finally {
            synchronized(lock) {
                val now = clock()
                acting--
                quietFrom = maxOf(quietFrom, now + OWN_WINDOW_TAIL_MILLIS)
                if (watching) lastNodeAction = now
                if (credit != null) {
                    if (!returned && !credit.used) credits.remove(credit) else credit.until = now + CREDIT_MILLIS
                }
            }
        }
    }

    /** A gesture or a settle wait of Kestrel's begins; each [begin] needs its [end]. */
    internal fun begin() = synchronized(lock) { acting++ }

    internal fun end() = synchronized(lock) {
        acting--
        quietFrom = maxOf(quietFrom, clock() + OWN_WINDOW_TAIL_MILLIS)
    }

    /** Whether Kestrel acts now, or acted less than [OWN_WINDOW_TAIL_MILLIS] ago. */
    internal fun busy(): Boolean = synchronized(lock) { acting > 0 || clock() < quietFrom }

    /** Classifies a touch that starts outside the watcher's window. */
    internal fun classify(deviceId: Int, inputSource: Int, toolType: Int, flags: Int, gestureRunning: Boolean): Touch = synchronized(lock) {
        val now = clock()
        val since = lastNodeAction?.let { now - it }
        fun touch(owner: Boolean, credited: Boolean = false) = Touch(owner, deviceId, inputSource, toolType, flags, since, credited)
        if (gestureRunning && deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD) return touch(owner = false)
        if (source.rule == TouchRule.FIELD && sdk >= FIELD_SDK && flags and ACCESSIBILITY_FLAG != 0) return touch(owner = false)
        if (credits()) {
            credits.removeAll { !it.used && it.until < now }
            val credit = credits.firstOrNull { !it.used }
            if (credit != null) {
                credit.used = true
                credits.remove(credit)
                return touch(owner = false, credited = true)
            }
        }
        touch(owner = true)
    }

    /**
     * One of Kestrel's gestures ended as cancelled: on Android 13 to 15 only a touch on the screen does that, so it counts
     * as the owner's touch on both branches (design D19 of sub-project 5; B35).
     */
    internal fun gestureCancelled() {
        val watched = synchronized(lock) { watching }
        if (watched && sdk <= LAST_CANCELLING_SDK) report(signal())
    }

    private fun credits(): Boolean = source.rule == TouchRule.CREDITS || (source.rule == TouchRule.FIELD && sdk < FIELD_SDK)

    override fun toString(): String = "OwnActions(source=$source, credits=${synchronized(lock) { credits.size }})"

    companion object {
        /** How long after Kestrel's click returned its echo may use its credit: twice the probe's bound of an echo's delay. */
        const val CREDIT_MILLIS = 400L

        /** How long after an action of Kestrel's the screen's events are still its own, for `OwnerSignal`. */
        const val OWN_WINDOW_TAIL_MILLIS = 300L

        /** An owner's touch this soon after Kestrel's last node action may be a late echo; the activity log says so. */
        const val LATE_ECHO_MILLIS = 1_000L

        /** The device of a touch that no input event carried: a signal of branch B or a cancelled gesture. */
        const val SIGNAL_DEVICE = -2

        /** The kinds whose echo reaches the watcher on Android 17 (plan 3C's evidence, which the probe checks). */
        val ECHOING: Set<Kind> = setOf(Kind.CLICK, Kind.LONG_CLICK)

        private const val FIELD_SDK = 37
        private const val LAST_CANCELLING_SDK = 35

        // MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT.
        private const val ACCESSIBILITY_FLAG = 0x800

        fun kindOf(action: Int): Kind = when (action) {
            AccessibilityNodeInfo.ACTION_CLICK -> Kind.CLICK
            AccessibilityNodeInfo.ACTION_LONG_CLICK -> Kind.LONG_CLICK
            AccessibilityNodeInfo.ACTION_SET_TEXT -> Kind.SET_TEXT
            AccessibilityNodeInfo.ACTION_FOCUS -> Kind.FOCUS
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> Kind.SCROLL
            else -> Kind.OTHER
        }

        /** The owner's touch that a signal found, without an input event. */
        fun signal(): Touch = Touch(owner = true, deviceId = SIGNAL_DEVICE, source = 0, toolType = 0, flags = 0, sinceNodeActionMillis = null, credited = false)
    }
}
