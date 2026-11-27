package dev.kestrel.app.phone

import dev.kestrel.app.phone.ScreenReadings.Broadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen source's readings are correct at the moment they are read (plan 7F's fourth and fifth re-checks). */
class ScreenReadingsTest {
    private var interactive = true
    private var keyguardLocked = true
    private var now = 0L
    private var onSourceThread = true
    private val caughtUp = mutableListOf<Long>()
    private val readings = ScreenReadings({ interactive }, { keyguardLocked }, { now }, { onSourceThread }, { caughtUp += it })

    // Android's Lockdown: after the owner's unlock, the lock screen comes back without a screen-off. The screen reads
    // as locked at once, at a tap's stamp and at the source's next reading, which has a new number.
    @Test
    fun aLockScreenThatComesBackWithoutAScreenOffReadsAsLockedAtOnce() {
        readings.heard(Broadcast.USER_PRESENT)
        keyguardLocked = false
        assertEquals(ScreenStatus(ScreenReading.UNLOCKED, offs = 0, seq = 2), readings.next())

        keyguardLocked = true
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 2), readings.status())
        assertTrue(readings.stale())
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 3), readings.next())
        assertFalse(readings.stale())
    }

    // The fifth re-check's N5-M2: a keyguard that catches up 1.5 s after USER_PRESENT. The unlock reads as unlocked for
    // its first second, then as locked, and the source goes on looking while the keyguard settles, so the look after the
    // keyguard caught up gives the unlock again with a new number, and the looks end.
    @Test
    fun theSourceLooksUntilASlowKeyguardCatchesUpAndThenReadsTheUnlockAgain() {
        assertEquals(ScreenStatus(ScreenReading.UNLOCKED, offs = 0, seq = 1), readings.heard(Broadcast.USER_PRESENT))
        assertTrue(readings.settling())
        now = ScreenReadings.UNLOCK_LAG_MILLIS - 1
        assertFalse(readings.stale())

        now = ScreenReadings.UNLOCK_LAG_MILLIS
        assertTrue(readings.stale())
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 2), readings.next())
        assertTrue(readings.settling())

        now = 1_500
        keyguardLocked = false
        assertTrue(readings.stale())
        assertEquals(ScreenStatus(ScreenReading.UNLOCKED, offs = 0, seq = 3), readings.next())
        assertFalse(readings.settling())
        assertEquals(listOf(1_500L), caughtUp)
    }

    // The source's looks are bounded: a keyguard that never catches up ends them after their time.
    @Test
    fun theLooksEndWhenTheKeyguardDoesNotSettleInTime() {
        readings.heard(Broadcast.USER_PRESENT)
        now = ScreenReadings.KEYGUARD_SETTLE_MILLIS
        assertFalse(readings.settling())
        assertEquals(ScreenReading.LOCKED, readings.next().reading)
        assertEquals(emptyList<Long>(), caughtUp)
    }

    // A screen-off counts, reads as off, and ends the keyguard's settling: the screen comes on locked.
    @Test
    fun aScreenOffCountsAndEndsTheUnlock() {
        readings.heard(Broadcast.USER_PRESENT)
        interactive = false
        assertEquals(ScreenStatus(ScreenReading.OFF, offs = 1, seq = 2), readings.heard(Broadcast.SCREEN_OFF))
        assertFalse(readings.settling())

        interactive = true
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 1, seq = 3), readings.heard(Broadcast.SCREEN_ON))
    }

    // The fifth re-check's N5-M1: a status read off the source's thread could not be in order with its reports, so the
    // source refuses it.
    @Test
    fun theSourceRefusesToReadOffItsThread() {
        onSourceThread = false
        val refused = assertThrows(IllegalStateException::class.java) { readings.status() }
        assertTrue(refused.message.orEmpty().startsWith("The screen source reads only on its own thread"))
    }
}
