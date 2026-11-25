package dev.kestrel.app.debug

import dev.kestrel.app.goal.ForegroundType
import org.junit.Assert.assertEquals
import org.junit.Test

/** The count of audio-focus requests beside the newest result (plan 7E, Task 6's review M3). */
class FocusRequestsTest {
    @Test
    fun aRequestCountsWhenAskedAndAgainWhenAnswered() {
        val requests = FocusRequests()
        assertEquals(FocusRecord(0, 0, null), requests.now())
        val first = FocusResult(granted = true, speaking = true, types = setOf(ForegroundType.SPECIAL_USE))
        val second = FocusResult(granted = true, speaking = true, types = setOf(ForegroundType.SPECIAL_USE))

        requests.asked()
        requests.answered(first)
        requests.asked()

        // The second request has not answered: its result, the same as the first's, cannot be read for it.
        assertEquals(FocusRecord(2, 1, first), requests.now())
        requests.answered(second)
        assertEquals(FocusRecord(2, 2, second), requests.now())
    }
}
