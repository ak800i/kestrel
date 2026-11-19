package dev.kestrel.app.listen

import android.content.pm.ServiceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When ListeningService counts as demoted (design spec, section 14.6; analysis of the A71 session of sub-project 2). */
class DemotionTest {
    private val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    @Test
    fun onAndroid13OnlyAndroidsListTellsBecauseTheTypeStays() {
        assertFalse(demotedFrom(listedForeground = true, type = types))
        assertTrue(demotedFrom(listedForeground = false, type = types))
        // A service that Android's list no longer names does not run in the foreground either.
        assertTrue(demotedFrom(listedForeground = null, type = types))
    }

    @Test
    fun aTypeOfZeroCountsAsDemotedAsOnAndroid14AndLater() {
        assertTrue(demotedFrom(listedForeground = true, type = 0))
    }
}
