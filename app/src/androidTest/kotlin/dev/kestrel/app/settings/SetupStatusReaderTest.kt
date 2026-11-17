package dev.kestrel.app.settings

import android.Manifest
import android.os.ParcelFileDescriptor
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
        val on = reader.read()
        assertTrue(on.serviceOn)
        assertTrue(on.serviceEnabled)

        screen.shell("settings delete secure enabled_accessibility_services")
        withTimeout(10_000) { (context.applicationContext as KestrelApplication).graph.screen.first { it == null } }

        val off = reader.read()
        assertFalse(off.serviceOn)
        assertFalse(off.serviceEnabled)
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
    fun seesTheShortcutTarget() {
        val saved = screen.shell("settings get secure $SHORTCUT").trim()
        try {
            putSecure(SHORTCUT, "dev.kestrel/dev.kestrel.app.KestrelAccessibilityService")
            assertTrue(reader.read().shortcutTarget)

            putSecure(SHORTCUT, "null")
            assertFalse(reader.read().shortcutTarget)
        } finally {
            putSecure(SHORTCUT, saved)
        }
    }

    /** Writes a secure setting through the device's shell, which reads quotes, so an empty value too; "null" deletes it. */
    private fun putSecure(name: String, value: String) {
        val command = if (value == "null") "settings delete secure $name" else "settings put secure $name '${value.replace("'", "'\\''")}'"
        val (output, input) = screen.automation.executeShellCommandRw("sh")
        ParcelFileDescriptor.AutoCloseOutputStream(input).use { it.write("$command\n".toByteArray()) }
        // The shell ends when its input ends, and only then its output, so the setting is written when this returns.
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
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

    private companion object {
        const val SHORTCUT = "accessibility_shortcut_target_service"
    }
}
