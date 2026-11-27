package dev.kestrel.app.phone

/**
 * The screen source's rule for what it reads (design 8.5 and 10.3 of sub-project 7; plan 7F's re-checks), apart from
 * Android's broadcasts and services, which [AndroidScreenSource] feeds in. Only Android's own state decides a reading,
 * at the moment it is read: [ScreenReading.OFF] while the screen is not [interactive], and otherwise
 * [ScreenReading.LOCKED] while the lock screen shows ([keyguardLocked], `KeyguardManager.isKeyguardLocked`, which a swipe
 * lock screen and Android's Lockdown both show), else [ScreenReading.UNLOCKED]. The broadcasts SCREEN_OFF, SCREEN_ON and
 * USER_PRESENT only make the source read again; none of them decides a reading, so their order does not matter.
 *
 * The keyguard can report the owner's unlock after USER_PRESENT, and Android gives a normal app no event when it does
 * (`addKeyguardLockedStateListener` needs `SUBSCRIBE_TO_KEYGUARD_LOCKED_STATE`, which only privileged apps hold). So after
 * SCREEN_ON and USER_PRESENT the source looks again every [KEYGUARD_POLL_MILLIS] ([settling]) until a look reads
 * [ScreenReading.UNLOCKED], a look sees the screen off, or [KEYGUARD_SETTLE_MILLIS] have passed; each look that reads
 * otherwise than the newest reading ([stale]) gives a new reading. A late keyguard therefore only delays the unlock: the
 * screen was already read as locked, so the change is one, from locked to unlocked. The first unlocked look after
 * USER_PRESENT tells [caughtUp] how long after USER_PRESENT it came.
 *
 * The count of screen-offs rises when a look sees the screen off, or a SCREEN_OFF broadcast comes, after the source last
 * saw the screen on. One screen-off counts once when no look sees the screen on between the look that counted it and its
 * broadcast; otherwise its late broadcast counts it again, which can only drop a held Continue (the owner's next tap
 * answers). A screen-off shorter than one look, during the looks after SCREEN_ON or USER_PRESENT and followed by the
 * owner's unlock before its broadcast arrives, is counted only after the unlocked reading, so a Continue held across it
 * can answer at that unlock, on a phone that the owner has unlocked. Android gives no way to tie a broadcast to the look
 * that saw the same screen-off, and it can merge fast screen-offs into one broadcast, so a rule that skipped broadcasts
 * would move the error to the unsafe side.
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
    /** The broadcasts that make the source read again. */
    enum class Broadcast { SCREEN_OFF, SCREEN_ON, USER_PRESENT }

    // The looks after SCREEN_ON or USER_PRESENT: since when, and whether USER_PRESENT started them.
    private data class Looks(val since: Long, val afterUnlock: Boolean)

    // One immutable state: the count of screen-offs, the number of the newest reading and that reading, whether the last
    // look or broadcast saw the screen off, and the looks while the keyguard may still change.
    private data class State(
        val offs: Long,
        val seq: Long,
        val given: ScreenReading?,
        val sawOff: Boolean,
        val looks: Looks?,
    )

    private var state = State(offs = 0, seq = 0, given = null, sawOff = false, looks = null)

    /** A [broadcast] came: the source reads again, and reports the new reading. */
    fun heard(broadcast: Broadcast): ScreenStatus {
        onThread()
        when (broadcast) {
            Broadcast.SCREEN_OFF -> screenOff()
            Broadcast.SCREEN_ON -> state = state.copy(looks = Looks(clock(), afterUnlock = false))
            Broadcast.USER_PRESENT -> state = state.copy(looks = Looks(clock(), afterUnlock = true))
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

    /** Whether the source reads otherwise now than its newest reading, which it then gives again ([next]). */
    fun stale(): Boolean {
        onThread()
        return state.given != null && look() != state.given
    }

    /** Whether the source goes on looking after SCREEN_ON or USER_PRESENT, because the keyguard may still change. */
    fun settling(): Boolean {
        onThread()
        val looks = state.looks ?: return false
        return clock() - looks.since < KEYGUARD_SETTLE_MILLIS
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

    // A look at Android's state now. A look that sees the screen off counts it, unless the source already saw it off;
    // one that sees the screen off or unlocked ends the looks.
    private fun look(): ScreenReading {
        if (!interactive()) {
            screenOff()
            return ScreenReading.OFF
        }
        state = state.copy(sawOff = false)
        if (keyguardLocked()) {
            val looks = state.looks
            if (looks != null && clock() - looks.since >= KEYGUARD_SETTLE_MILLIS) state = state.copy(looks = null)
            return ScreenReading.LOCKED
        }
        state.looks?.let { looks ->
            if (looks.afterUnlock) caughtUp(clock() - looks.since)
            state = state.copy(looks = null)
        }
        return ScreenReading.UNLOCKED
    }

    // The screen went off: counted once, however many looks and broadcasts see it, and the looks end.
    private fun screenOff() {
        state = state.copy(offs = if (state.sawOff) state.offs else state.offs + 1, sawOff = true, looks = null)
    }

    companion object {
        /** How often the source looks at the keyguard after SCREEN_ON and USER_PRESENT. */
        const val KEYGUARD_POLL_MILLIS = 250L

        /** How long after SCREEN_ON or USER_PRESENT the source looks at the keyguard, at most. */
        const val KEYGUARD_SETTLE_MILLIS = 5_000L
    }
}
