package dev.kestrel.app.phone

/**
 * The screen source's rule for what it reads (design 8.5 and 10.3 of sub-project 7; plan 7F's fourth and fifth
 * re-checks), apart from Android's broadcasts and services, which [AndroidScreenSource] feeds in. A reading is correct
 * at the moment it is read: the screen is [ScreenReading.OFF] while it is not [interactive], and otherwise
 * [ScreenReading.LOCKED] while the lock screen shows ([keyguardLocked], `KeyguardManager.isKeyguardLocked`, which a swipe
 * lock screen and Android's Lockdown both show), else [ScreenReading.UNLOCKED].
 *
 * KeyguardManager can learn of the owner's unlock after USER_PRESENT, and Android gives a normal app no event when it
 * does (`addKeyguardLockedStateListener` needs `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE`, which only privileged apps hold).
 * So after USER_PRESENT the keyguard is *settling*: the source looks again every [KEYGUARD_POLL_MILLIS] ([settling])
 * until a look sees the keyguard unlocked, a screen-off comes, or [KEYGUARD_SETTLE_MILLIS] have passed, and each look
 * that reads otherwise than the newest reading ([stale]) gives a new reading. While the keyguard settles, the screen
 * reads as unlocked although the keyguard may still say locked: USER_PRESENT is SystemUI's word that the owner unlocked,
 * which the keyguard is only late to report, so a late keyguard never reads as a lock for a moment. Only a keyguard that
 * has not caught up when that time ends reads as locked, with a new reading. The first look that sees the keyguard
 * unlocked ends the settling, within one poll of the catch-up, so a lock screen that comes back later without a
 * screen-off (Lockdown) reads as locked at once; that look tells [caughtUp] how long after USER_PRESENT it was.
 *
 * Every call runs on the source's one thread ([onSourceThread]), which numbers the readings in the order of its looks and
 * broadcasts, and refuses to run elsewhere: a status read on another thread could not be in order with the reports.
 */
internal class ScreenReadings(
    private val interactive: () -> Boolean,
    private val keyguardLocked: () -> Boolean,
    private val clock: () -> Long,
    private val onSourceThread: () -> Boolean,
    private val caughtUp: (millis: Long) -> Unit = {},
) {
    /** The broadcasts that change the screen's reading. */
    enum class Broadcast { SCREEN_OFF, SCREEN_ON, USER_PRESENT }

    // One immutable state: when USER_PRESENT came while the keyguard settles, the count of screen-offs, the number of the
    // newest reading, and that reading.
    private data class State(val presentAt: Long?, val offs: Long, val seq: Long, val given: ScreenReading?)

    private var state = State(presentAt = null, offs = 0, seq = 0, given = null)

    /** A [broadcast] came: the source's new reading, which it reports. */
    fun heard(broadcast: Broadcast): ScreenStatus {
        onThread()
        state = when (broadcast) {
            Broadcast.USER_PRESENT -> state.copy(presentAt = clock())
            Broadcast.SCREEN_OFF -> state.copy(presentAt = null, offs = state.offs + 1)
            Broadcast.SCREEN_ON -> state
        }
        return next()
    }

    /** A new reading of the source, numbered after every earlier one. */
    fun next(): ScreenStatus {
        onThread()
        val reading = look()
        state = state.copy(seq = state.seq + 1, given = reading)
        return ScreenStatus(reading, state.offs, state.seq)
    }

    /** Whether the source would read otherwise now than its newest reading, which it then gives again ([next]). */
    fun stale(): Boolean {
        onThread()
        return state.given != null && look() != state.given
    }

    /** Whether the keyguard is still settling after USER_PRESENT, so that the source looks again. */
    fun settling(): Boolean {
        onThread()
        val present = state.presentAt ?: return false
        return clock() - present < KEYGUARD_SETTLE_MILLIS
    }

    /** The reading now, with the count of screen-offs and the number of the newest reading that the source gave. */
    fun status(): ScreenStatus {
        onThread()
        val reading = look()
        return ScreenStatus(reading, state.offs, state.seq)
    }

    private fun onThread() {
        check(onSourceThread()) {
            "The screen source reads only on its own thread, the main thread, so that each reading is in order with " +
                "its reports"
        }
    }

    // A look at the screen now, which ends the keyguard's settling when it sees the keyguard unlocked or when its time has
    // passed.
    private fun look(): ScreenReading {
        if (!interactive()) return ScreenReading.OFF
        val present = state.presentAt
        val since = present?.let { clock() - it }
        if (!keyguardLocked()) {
            if (since != null) {
                state = state.copy(presentAt = null)
                caughtUp(since)
            }
            return ScreenReading.UNLOCKED
        }
        if (since == null) return ScreenReading.LOCKED
        if (since < KEYGUARD_SETTLE_MILLIS) return ScreenReading.UNLOCKED
        state = state.copy(presentAt = null)
        return ScreenReading.LOCKED
    }

    companion object {
        /** How often the source looks at the keyguard while it settles after USER_PRESENT. */
        const val KEYGUARD_POLL_MILLIS = 250L

        /** How long after USER_PRESENT the source looks at the keyguard and reads the screen as unlocked, at most. */
        const val KEYGUARD_SETTLE_MILLIS = 5_000L
    }
}
