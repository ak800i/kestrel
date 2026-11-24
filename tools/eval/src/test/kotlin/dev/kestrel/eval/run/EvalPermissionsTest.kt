package dev.kestrel.eval.run

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's permission set for the evaluation, granted before a live series and kept (his decision of 2026-11-24). */
class EvalPermissionsTest {
    @Test
    fun theSetIsGrantedReadBackAndKeptAndAMissingOneStopsTheSeries() {
        val run = FakeRun(DeviceKind.A71)
        run.adb.granted["dev.kestrel"] = mutableSetOf("android.permission.READ_CONTACTS")

        val states = EvalPermissions.grant(run.device)

        assertEquals(EvalPermissions.SET.associateWith { true }, states)
        assertEquals(
            EvalPermissions.SET.filter { it != "android.permission.READ_CONTACTS" }.map { "pm grant dev.kestrel $it" },
            run.adb.commands.filter { it.startsWith("pm grant") },
        )
        assertTrue(run.adb.commands.none { it.startsWith("pm revoke") })

        // A grant that Android leaves undone without an error, as with a hard-restricted permission of a Kestrel that was
        // installed without the installer's exemption, or a grant that fails, stops the series before any run; the refusal
        // names them and the install that exempts them, because Settings does not offer such a permission either.
        run.adb.granted.getValue("dev.kestrel").removeAll(listOf("android.permission.READ_SMS", "android.permission.READ_CALL_LOG"))
        run.adb.refusedGrants += "android.permission.READ_SMS"
        run.adb.on("^pm grant dev\\.kestrel android\\.permission\\.READ_CALL_LOG$") { null }
        val message = assertThrows(Refusal::class.java) { EvalPermissions.grant(run.device) }.message!!
        assertTrue(message, message.contains("android.permission.READ_SMS, android.permission.READ_CALL_LOG"))
        assertTrue(message, message.contains("scripts\\install.ps1"))
        assertFalse(message, message.contains("Settings"))
        assertTrue(run.adb.commands.none { it.startsWith("pm revoke") })
    }
}
