package dev.kestrel.app

import dev.kestrel.app.goal.NoticePlace
import dev.kestrel.app.listen.ListeningService
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.providers.SignInService
import org.junit.Assert.assertEquals
import org.junit.Test

/** Kestrel's notifications by their IDs (plan 7E, Task 8's Step 8a). */
class KestrelNotificationTest {
    @Test
    fun everyKindOfKestrelsNotificationsHasAnIdOfItsOwn() {
        // Android keeps one notification per ID: the accessibility notice posted with the held goal's ID replaced it.
        val ids = listOf(
            ListeningService.NOTIFICATION_ID,
            ProblemNotifications.STOPPED_ID,
            ProblemNotifications.ACCESSIBILITY_ID,
            ProblemNotifications.SETTINGS_ID,
            SignInService.NOTIFICATION_ID,
            NoticePlace.SERVICE.id,
            NoticePlace.POSTED.id,
        )

        assertEquals(ids.size, ids.toSet().size)
    }
}
