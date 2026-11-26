package dev.kestrel.app.listen

import org.junit.Assert.assertEquals
import org.junit.Test

/** The call watch after a grant of READ_PHONE_STATE outside Kestrel (section 8.7; review B-M2 of plan 7F). */
class PhoneStateGrantTest {
    @Test
    fun aGrantStartsTheCallWatchOnceUntilItIsTakenBackAndGivenAgain() {
        var granted = false
        var watches = 0
        val grant = PhoneStateGrant({ granted }) { watches++ }

        grant.check()
        granted = true
        grant.check()
        grant.check()
        assertEquals(1, watches)

        granted = false
        grant.check()
        granted = true
        grant.check()
        assertEquals(2, watches)
    }
}
