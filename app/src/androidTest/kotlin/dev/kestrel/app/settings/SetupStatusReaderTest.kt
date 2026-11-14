package dev.kestrel.app.settings

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.screen.ScreenServiceRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SetupStatusReaderTest {
    @get:Rule
    val screen = ScreenServiceRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val reader = SetupStatusReader(context)

    @Test
    fun seesTheServiceTurnOff() = runBlocking {
        assertTrue(reader.read().serviceOn)

        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { (context.applicationContext as KestrelApplication).graph.screen.first { it == null } }

        assertFalse(reader.read().serviceOn)
    }

    // Revoking a permission would stop the app's process, and the tests with it, so this test only grants them.
    @Test
    fun seesTheGrantedPermissions() {
        screen.automation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        screen.automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)

        val status = reader.read()
        assertTrue(status.microphoneAllowed)
        assertTrue(status.notificationsAllowed)
    }

    @Test
    fun seesUnrestrictedBatteryUse() {
        try {
            screen.shell("dumpsys deviceidle whitelist +${context.packageName}")
            assertTrue(reader.read().batteryUnrestricted)

            screen.shell("dumpsys deviceidle whitelist -${context.packageName}")
            assertFalse(reader.read().batteryUnrestricted)
        } finally {
            screen.shell("dumpsys deviceidle whitelist -${context.packageName}")
        }
    }
}
