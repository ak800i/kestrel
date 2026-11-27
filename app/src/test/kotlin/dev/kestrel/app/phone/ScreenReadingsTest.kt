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

    // The fifth and sixth re-checks' N5-M2 and N6-M1: a keyguard that catches up 1.5 s after USER_PRESENT. The screen
    // reads as unlocked for the whole time that the source looks, so no look gives a reading, no LOCKED comes between,
    // and the catch-up ends the looks.
    @Test
    fun aKeyguardThatIsLateGivesNoLockedReadingAndItsCatchUpEndsTheLooks() {
        assertEquals(ScreenStatus(ScreenReading.UNLOCKED, offs = 0, seq = 1), readings.heard(Broadcast.USER_PRESENT))
        for (at in listOf(1_000L, 1_250L)) {
            now = at
            assertFalse(readings.stale())
            assertTrue(readings.settling())
        }

        now = 1_500
        keyguardLocked = false
        assertFalse(readings.stale())
        assertFalse(readings.settling())
        assertEquals(listOf(1_500L), caughtUp)
        assertEquals(ScreenStatus(ScreenReading.UNLOCKED, offs = 0, seq = 1), readings.status())
    }

    // The source's looks are bounded: a keyguard that has not caught up when they end reads as locked, with a new reading.
    @Test
    fun aKeyguardThatDoesNotSettleInTimeReadsAsLockedWhenTheLooksEnd() {
        readings.heard(Broadcast.USER_PRESENT)
        now = ScreenReadings.KEYGUARD_SETTLE_MILLIS - 1
        assertFalse(readings.stale())

        now = ScreenReadings.KEYGUARD_SETTLE_MILLIS
        assertFalse(readings.settling())
        assertTrue(readings.stale())
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 2), readings.next())
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
