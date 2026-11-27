package dev.kestrel.app.phone

import dev.kestrel.app.phone.ScreenReadings.Broadcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only Android's own state decides the screen source's readings (plan 7F's seventh re-check). */
class ScreenReadingsTest {
    private var interactive = true
    private var keyguardLocked = true
    private var now = 0L
    private var onSourceThread = true
    private val caughtUp = mutableListOf<Long>()
    private val readings = ScreenReadings({ interactive }, { keyguardLocked }, { now }, { onSourceThread }, { caughtUp += it })

    // USER_PRESENT before the keyguard reports the unlock: the screen stays locked until the keyguard says unlocked, and
    // the source's looks then give one change, from locked to unlocked, and end.
    @Test
    fun aUserPresentBeforeTheKeyguardCatchesUpReadsNoUnlockUntilItDoes() {
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 1), readings.heard(Broadcast.SCREEN_ON))
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 2), readings.heard(Broadcast.USER_PRESENT))
        for (at in listOf(250L, 1_000L, 1_250L)) {
            now = at
            assertTrue(readings.settling())
            assertFalse(readings.stale())
            assertEquals(ScreenReading.LOCKED, readings.status().reading)
        }

        now = 1_500
        keyguardLocked = false
        assertTrue(readings.stale())
        assertEquals(ScreenStatus(ScreenReading.UNLOCKED, offs = 0, seq = 3), readings.next())
        assertFalse(readings.settling())
        assertFalse(readings.stale())
        assertEquals(listOf(1_500L), caughtUp)
    }

    // Android delivers SCREEN_OFF before a USER_PRESENT that SystemUI sent earlier: the late USER_PRESENT reads the screen
    // off and starts no looks, and the screen comes on locked.
    @Test
    fun aScreenOffHeardBeforeUserPresentGivesNoUnlockOnTheLockScreen() {
        interactive = false
        assertEquals(ScreenStatus(ScreenReading.OFF, offs = 1, seq = 1), readings.heard(Broadcast.SCREEN_OFF))
        assertEquals(ScreenStatus(ScreenReading.OFF, offs = 1, seq = 2), readings.heard(Broadcast.USER_PRESENT))
        assertFalse(readings.settling())

        interactive = true
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 1, seq = 3), readings.heard(Broadcast.SCREEN_ON))
    }

    // Android's Lockdown: the lock screen comes back without a screen-off. The screen reads as locked at once, at a tap's
    // stamp and at the source's next reading, which has a new number.
    @Test
    fun aLockScreenThatComesBackWithoutAScreenOffReadsAsLockedAtOnce() {
        keyguardLocked = false
        assertEquals(ScreenStatus(ScreenReading.UNLOCKED, offs = 0, seq = 1), readings.heard(Broadcast.USER_PRESENT))

        keyguardLocked = true
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 1), readings.status())
        assertTrue(readings.stale())
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 2), readings.next())
        assertFalse(readings.stale())
    }

    // The looks are bounded: a keyguard that still says locked after them keeps the screen locked, with no new reading.
    @Test
    fun theLooksEndAfterTheirTimeAndTheScreenStaysLocked() {
        readings.heard(Broadcast.USER_PRESENT)
        now = ScreenReadings.KEYGUARD_SETTLE_MILLIS
        assertFalse(readings.settling())
        assertFalse(readings.stale())
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 0, seq = 1), readings.status())
        assertEquals(emptyList<Long>(), caughtUp)
    }

    // A screen-off counts once, whether a look or the SCREEN_OFF broadcast saw it first, and ends the looks.
    @Test
    fun aScreenOffCountsOnceWhicheverSawItFirst() {
        readings.heard(Broadcast.SCREEN_ON)
        interactive = false
        assertEquals(ScreenStatus(ScreenReading.OFF, offs = 1, seq = 1), readings.status())
        assertFalse(readings.settling())
        assertEquals(ScreenStatus(ScreenReading.OFF, offs = 1, seq = 2), readings.heard(Broadcast.SCREEN_OFF))

        interactive = true
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 1, seq = 3), readings.heard(Broadcast.SCREEN_ON))
        interactive = false
        assertEquals(ScreenStatus(ScreenReading.OFF, offs = 2, seq = 4), readings.heard(Broadcast.SCREEN_OFF))
    }

    // The eighth re-check's N8-M1: a look sees the screen off, a tap's stamp then sees it on again, and the off's own
    // SCREEN_OFF broadcast comes after that: Android ties no broadcast to a look, so the late broadcast counts the same
    // screen-off again. That can only drop a Continue held in the gap (the safe side), and the owner's next tap answers.
    @Test
    fun aLateScreenOffBroadcastAfterALookSawTheScreenOnCountsTheOffAgain() {
        readings.heard(Broadcast.SCREEN_ON)
        interactive = false
        assertEquals(ScreenStatus(ScreenReading.OFF, offs = 1, seq = 1), readings.status())

        interactive = true
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 1, seq = 1), readings.status())
        assertEquals(ScreenStatus(ScreenReading.LOCKED, offs = 2, seq = 2), readings.heard(Broadcast.SCREEN_OFF))
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
