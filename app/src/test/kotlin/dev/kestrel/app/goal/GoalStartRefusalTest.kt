package dev.kestrel.app.goal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug seam that refuses the goal service's next start (plan 7E, Task 6's review M2): an arm refuses one start, and
 * a take, by that start or by goal-control's disarm, leaves nothing armed and says whether something was.
 */
class GoalStartRefusalTest {
    @Test
    fun anArmedRefusalIsTakenOnceAndThenNothingIsArmed() {
        val refusal = GoalStartRefusal()
        assertFalse(refusal.armed)
        assertFalse(refusal.take())

        refusal.arm()
        refusal.arm()

        assertTrue(refusal.armed)
        assertTrue(refusal.take())
        assertFalse(refusal.armed)
        assertFalse(refusal.take())
    }
}
