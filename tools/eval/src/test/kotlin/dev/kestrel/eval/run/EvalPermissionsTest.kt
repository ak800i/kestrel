package dev.kestrel.eval.run

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The owner's kept set for the evaluation: Kestrel's seven data-tool permissions and its notification access, granted
 * before a live series and kept (his decisions of 2026-11-24).
 */
class EvalPermissionsTest {
    private val listener = EvalAccess.NOTIFICATION_LISTENER.id

    @Test
    fun theSetIsGrantedReadBackAndKeptAndAMissingOneStopsTheSeries() {
        val run = FakeRun(DeviceKind.A71)
        run.adb.granted["dev.kestrel"] = mutableSetOf("android.permission.READ_CONTACTS")

        val states = EvalPermissions.grant(run.device, run.clock)

        assertEquals(EvalAccess.entries.associate { it.id to true }, states)
        assertEquals(
            EvalAccess.entries.filter { it.kind == EvalAccess.Kind.RUNTIME_PERMISSION && it != EvalAccess.READ_CONTACTS }.map { "pm grant dev.kestrel ${it.id}" },
            run.adb.commands.filter { it.startsWith("pm grant") },
        )
        assertEquals(listOf("cmd notification allow_listener $listener"), run.adb.commands.filter { it.startsWith("cmd notification") })
        assertTrue(run.adb.commands.none { it.startsWith("pm revoke") })

        // A grant that Android leaves undone without an error, as with a hard-restricted permission of a Kestrel that was
        // installed without the installer's exemption, or a grant that fails, stops the series before any run; the refusal
        // names them and the install that exempts them, because Settings does not offer such a permission either.
        run.adb.granted.getValue("dev.kestrel").removeAll(listOf("android.permission.READ_SMS", "android.permission.READ_CALL_LOG"))
        run.adb.refusedGrants += "android.permission.READ_SMS"
        run.adb.on("^pm grant dev\\.kestrel android\\.permission\\.READ_CALL_LOG$") { null }
        val message = assertThrows(Refusal::class.java) { EvalPermissions.grant(run.device, run.clock) }.message!!
        assertTrue(message, message.contains("android.permission.READ_SMS, android.permission.READ_CALL_LOG"))
        assertTrue(message, message.contains("scripts\\install.ps1"))
        assertFalse(message, message.contains("notification access"))
        assertTrue(run.adb.commands.none { it.startsWith("pm revoke") })
    }

    @Test
    fun notificationAccessIsAllowedReadBackFromItsSettingAndKept() {
        val run = FakeRun()
        // Android writes the setting a moment after allow_listener; another app's listener stays.
        run.adb.settings["secure/enabled_notification_listeners"] = "com.example.watch/com.example.watch.Listener"
        run.adb.listenerLag = 2

        assertEquals(mapOf(EvalAccess.NOTIFICATION_LISTENER to true), EvalPermissions.keep(run.device, run.clock, listOf(EvalAccess.NOTIFICATION_LISTENER)))
        assertEquals("com.example.watch/com.example.watch.Listener:$listener", run.adb.settings["secure/enabled_notification_listeners"])

        // An access that the setting already names, also in its short form, is only read.
        val short = FakeRun()
        short.adb.settings["secure/enabled_notification_listeners"] = "dev.kestrel/.datatools.notifications.KestrelNotificationListener"
        assertEquals(mapOf(EvalAccess.NOTIFICATION_LISTENER to true), EvalPermissions.keep(short.device, short.clock, listOf(EvalAccess.NOTIFICATION_LISTENER)))
        assertTrue(short.adb.commands.none { it.startsWith("cmd notification") })
    }

    @Test
    fun aLiveSeriesStopsWhenNotificationAccessStaysOffAndSaysHowToGiveIt() {
        val run = FakeRun()
        run.adb.refusedListeners += listener

        val message = assertThrows(Refusal::class.java) { EvalPermissions.grant(run.device, run.clock) }.message!!

        assertTrue(message, message.contains("notification access"))
        assertTrue(message, message.contains("Notification access"))
        assertFalse(message, message.contains("pm grant"))
        assertTrue(run.adb.commands.none { it.startsWith("cmd notification disallow_listener") })
    }
}
