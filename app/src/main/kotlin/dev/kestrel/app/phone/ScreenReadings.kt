package dev.kestrel.app.phone

/**
 * The screen source's rule for what it reads (design 8.5 and 10.3 of sub-project 7; plan 7F's fourth re-check), apart
 * from Android's broadcasts and services, which [AndroidScreenSource] feeds in. A reading is correct at the moment it
 * is read: the screen is [ScreenReading.OFF] while it is not [interactive], and otherwise [ScreenReading.LOCKED] while
 * the lock screen shows ([keyguardLocked], `KeyguardManager.isKeyguardLocked`, which a swipe lock screen and Android's
 * Lockdown both show), else [ScreenReading.UNLOCKED].
 *
 * One exception bridges a lag: KeyguardManager can learn of the owner's unlock a moment after USER_PRESENT, so for
 * [UNLOCK_LAG_MILLIS] after USER_PRESENT the screen reads as unlocked although the keyguard still says locked. The bridge
 * ends at the first reading that sees the keyguard unlocked, at the end of that time, and at a screen-off, so a lock
 * screen that comes back later without a screen-off (Lockdown) reads as locked at once. When what the source would read
 * now differs from the newest reading that it gave ([stale]), the source gives it as a new reading.
 *
 * [heard], [next] and [stale] run on the source's one thread, which numbers the readings in their order; [status] may
 * run on any thread.
 */
internal class ScreenReadings(
    private val interactive: () -> Boolean,
    private val keyguardLocked: () -> Boolean,
    private val clock: () -> Long,
) {
    /** The broadcasts that change the screen's reading. */
    enum class Broadcast { SCREEN_OFF, SCREEN_ON, USER_PRESENT }

    // When USER_PRESENT came, while its bridge lasts.
    @Volatile
    private var presentAt: Long? = null

    @Volatile
    private var offs = 0L

    @Volatile
    private var seq = 0L

    // The newest reading that the source gave.
    private var given: ScreenReading? = null

    /** A [broadcast] came: the source's new reading, which it reports. */
    fun heard(broadcast: Broadcast): ScreenStatus {
        when (broadcast) {
            Broadcast.USER_PRESENT -> presentAt = clock()
            Broadcast.SCREEN_OFF -> {
                presentAt = null
                offs++
            }
            Broadcast.SCREEN_ON -> Unit
        }
        return next()
    }

    /** The source starts again: an unlock that it heard before is no longer its to bridge. */
    fun restarted() {
        presentAt = null
    }

    /** A new reading of the source, numbered after every earlier one. */
    fun next(): ScreenStatus {
        val reading = look(endBridge = true)
        seq++
        given = reading
        return ScreenStatus(reading, offs, seq)
    }

    /** Whether the source would read otherwise now than its newest reading, which it then gives again ([next]). */
    fun stale(): Boolean = given != null && look(endBridge = true) != given

    /** The reading now, with the count of screen-offs and the number of the newest reading that the source gave. */
    fun status(): ScreenStatus = ScreenStatus(look(endBridge = false), offs, seq)

    // Only the source's thread ends the bridge, so that another thread's look never ends a newer USER_PRESENT's.
    private fun look(endBridge: Boolean): ScreenReading {
        if (!interactive()) return ScreenReading.OFF
        if (!keyguardLocked()) {
            if (endBridge) presentAt = null
            return ScreenReading.UNLOCKED
        }
        val present = presentAt ?: return ScreenReading.LOCKED
        if (clock() - present < UNLOCK_LAG_MILLIS) return ScreenReading.UNLOCKED
        if (endBridge) presentAt = null
        return ScreenReading.LOCKED
    }

    companion object {
        /** How long USER_PRESENT counts as the unlock while KeyguardManager has not caught up with it. */
        const val UNLOCK_LAG_MILLIS = 1_000L
    }
}
