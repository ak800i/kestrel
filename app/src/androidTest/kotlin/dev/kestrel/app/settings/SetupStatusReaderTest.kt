package dev.kestrel.app.settings

import android.Manifest
import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
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
        val on = reader.read()
        assertTrue(on.serviceOn)
        assertTrue(on.serviceEnabled)

        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { (context.applicationContext as KestrelApplication).graph.screen.first { it == null } }

        val off = reader.read()
        assertFalse(off.serviceOn)
        assertFalse(off.serviceEnabled)
    }

    @Test
    fun seesRestrictedBatteryUse() {
        // On a phone, a test process that died between the two commands would leave Kestrel restricted, and no script
        // puts the op back. Before the try block, so that a phone runs no command.
        VoiceTests.assumeEmulator()
        try {
            // What the battery setting "Restricted" sets (prototype P2).
            screen.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND ignore")
            assertTrue(reader.read().batteryRestricted)

            // The op's normal mode; "default" would still count as restricted (prototype P2).
            screen.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow")
            assertFalse(reader.read().batteryRestricted)
        } finally {
            screen.shell("cmd appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow")
        }
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
