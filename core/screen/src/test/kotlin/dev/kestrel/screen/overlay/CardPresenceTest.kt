package dev.kestrel.screen.overlay

import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPresenceTest {
    private var now = 10_000L
    private val card = CardPresence { now }
    private val bounds = IntRect(40, 1800, 1040, 2200)

    @Test
    fun theCardCountsAsShownWithItsBoundsForOneSettleAfterItEnded() {
        card.shown()
        card.laidOut(bounds)
        now += 5_000
        card.closed()

        now += CardPresence.CLOSING_MILLIS - 1
        assertTrue(card.isShowing)
        assertEquals(bounds, card.bounds)

        now += 1
        assertFalse(card.isShowing)
        assertNull(card.bounds)
    }

    @Test
    fun aNewCardHasNoBoundsUntilItIsLaidOut() {
        assertFalse(card.isShowing)
        card.shown()
        card.laidOut(bounds)
        card.closed()
        now += 1_000

        card.shown()
        assertTrue(card.isShowing)
        assertNull(card.bounds)
    }
}
