package dev.kestrel.app.phone

import dev.kestrel.app.phone.ScreenReadings.Broadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screen source's readings are correct at the moment they are read (plan 7F's fourth re-check). */
class ScreenReadingsTest {
    private var interactive = true
    private var keyguardLocked = true
    private var now = 0L
    private val readings = ScreenReadings({ interactive }, { keyguardLocked }, { now })

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

    // USER_PRESENT counts as the unlock while KeyguardManager has not caught up with it, and no longer than that.
    @Test
    fun theUnlockBridgesTheKeyguardsLagOnlyForItsTime() {
        assertEquals(ScreenStatus(ScreenReading.UNLOCKED, offs = 0, seq = 1), readings.heard(Broadcast.USER_PRESENT))
        now = ScreenReadings.UNLOCK_LAG_MILLIS - 1
        assertEquals(ScreenReading.UNLOCKED, readings.status().reading)
        assertFalse(readings.stale())

        now = ScreenReadings.UNLOCK_LAG_MILLIS
        assertEquals(ScreenReading.LOCKED, readings.status().reading)
        assertTrue(readings.stale())
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 2), readings.next())
    }

    // A screen-off counts, reads as off, and ends the bridge: the screen comes on locked.
    @Test
    fun aScreenOffCountsAndEndsTheUnlock() {
        readings.heard(Broadcast.USER_PRESENT)
        interactive = false
        assertEquals(ScreenStatus(ScreenReading.OFF, offs = 1, seq = 2), readings.heard(Broadcast.SCREEN_OFF))

        interactive = true
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 1, seq = 3), readings.heard(Broadcast.SCREEN_ON))
    }
}
