package dev.kestrel.datatools.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsLocationTest {
    @Test
    fun aFixIsKestrelsOwnLineWithItsAccuracyAgeAndWhetherItIsApproximate() {
        assertEquals(
            "latitude 44.82310, longitude 20.45110, accuracy 12 m, 3 s old, approximate: false",
            GpsLocation.line(44.8231, 20.4511, 12.4f, 3_400, approximate = false),
        )
        assertEquals("latitude -33.86000, longitude 151.21000, accuracy 2000 m, 2 min old, approximate: true", GpsLocation.line(-33.86, 151.21, 2000f, 150_000, true))
        assertEquals("3 h", GpsLocation.age(3 * 3_600_000L + 5))
        // Android answers at once, with no fix, when it does not let an app ask for the location from the background;
        // a search for satellites that finds none takes up to 30 s.
        assertTrue(GpsLocation.refusedInBackground(150))
        assertFalse(GpsLocation.refusedInBackground(GpsLocation.WAIT_MILLIS))
    }
}
