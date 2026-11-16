package dev.kestrel.app

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.listen.ProblemNotifications
import dev.kestrel.app.screen.ScreenServiceRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * The notification that says that Kestrel's accessibility service is off (design spec, section 14.6). The test grants
 * the notification permission, which stays granted, as the voice tests leave it.
 */
class AccessibilityNoticeTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (context.applicationContext as KestrelApplication).graph
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Test
    fun aNotificationSaysThatTheServiceIsOffUntilItConnectsAgain() = runBlocking {
        screen.automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        try {
            screen.shell("settings delete secure enabled_accessibility_services")
            withTimeout(10_000) { graph.screen.first { it == null } }

            val notice = withTimeoutOrNull(5_000) {
                var found: Notification? = null
                while (found == null) {
                    found = notifications.activeNotifications.firstOrNull { it.id == ProblemNotifications.ACCESSIBILITY_ID }
                        ?.notification
                    if (found == null) delay(50)
                }
                found
            }
            assertNotNull("No notification says that the accessibility service is off", notice)
            assertEquals(
                context.getString(R.string.problem_service_off_title),
                notice!!.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            )

            screen.shell("settings put secure enabled_accessibility_services $SERVICE")
            screen.shell("settings put secure accessibility_enabled 1")
            withTimeout(10_000) { graph.screen.filterNotNull().first() }
            val gone = withTimeoutOrNull(5_000) {
                while (notifications.activeNotifications.any { it.id == ProblemNotifications.ACCESSIBILITY_ID }) delay(50)
                true
            }
            assertEquals("The notification is still there after the service connected again", true, gone)
        } finally {
            // Also after a failure, so that the next test class finds the service on.
            screen.shell("settings put secure enabled_accessibility_services $SERVICE")
            screen.shell("settings put secure accessibility_enabled 1")
            notifications.cancel(ProblemNotifications.ACCESSIBILITY_ID)
        }
    }

    private companion object {
        const val SERVICE = "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService"
    }
}
