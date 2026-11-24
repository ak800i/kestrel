package dev.kestrel.eval.run

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's permission set for the evaluation, granted before a live series and kept (his decision of 2026-11-24). */
class EvalPermissionsTest {
    @Test
    fun theSetIsGrantedReadBackAndKeptAndAMissingOneStopsTheSeries() {
        val run = FakeRun(DeviceKind.A71)
        val granted = mutableSetOf("android.permission.READ_CONTACTS")
        var refused: String? = null
        run.adb.on("^dumpsys package dev\\.kestrel$") {
            "  Package [dev.kestrel] (316e119):\n    runtime permissions:\n" + granted.joinToString("\n") { "      $it: granted=true, flags=[ USER_SET ]" }
        }
        run.adb.on("^pm grant dev\\.kestrel (\\S+)$") { match -> match.groupValues[1].takeIf { it != refused }?.let { granted.add(it); "" } }

        val states = EvalPermissions.grant(run.device)

        assertEquals(EvalPermissions.SET.associateWith { true }, states)
        assertEquals(
            EvalPermissions.SET.filter { it != "android.permission.READ_CONTACTS" }.map { "pm grant dev.kestrel $it" },
            run.adb.commands.filter { it.startsWith("pm grant") },
        )
        assertTrue(run.adb.commands.none { it.startsWith("pm revoke") })

        // A grant that Android refuses stops the series before any run, and names what is missing.
        granted.remove("android.permission.READ_SMS")
        refused = "android.permission.READ_SMS"
        val refusal = assertThrows(Refusal::class.java) { EvalPermissions.grant(run.device) }
        assertTrue(refusal.message, refusal.message!!.contains("android.permission.READ_SMS"))
        assertTrue(run.adb.commands.none { it.startsWith("pm revoke") })
    }
}
