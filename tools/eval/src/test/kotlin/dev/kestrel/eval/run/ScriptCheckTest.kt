package dev.kestrel.eval.run

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.task.FakeRun
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

/**
 * The check of a command's scripts before its first run (plan 7G): Kestrel's own scripted model reads each one, filled as
 * a run fills it, and a script that it refuses stops the command with the file and Kestrel's reason, never a text.
 */
class ScriptCheckTest {
    private val run = FakeRun()
    private val checked = mutableListOf<String>()
    private val check = ScriptCheck(ScriptInstaller(run.device, run.hooks, run.scope.work), run.device.serial)

    // The text that the check put on the device, as Kestrel reads it.
    private fun sent(): String = Files.readString(run.scope.work.resolve("check.json"))

    @Test
    fun everyScriptIsReadOnceFilledAsARunFillsItWithNumbersForTheHourAndTheMinute() {
        run.adb.hook("AGENT_SCRIPT") { extras ->
            checked += sent()
            if (extras["check"] == "true") "script=valid replies=2" else "script=installed replies=2"
        }

        check.check(listOf("alarm.json", "wifi-settings.json", "alarm.json"))

        assertEquals(listOf(mapOf("file" to "check.json", "check" to "true"), mapOf("file" to "check.json", "check" to "true")), run.adb.hookCalls("AGENT_SCRIPT"))
        // The alarm's hour and minute are numbers of its JSON, which only a filled script holds.
        checked.forEach { Json.parseToJsonElement(it).jsonArray }
        assertEquals(2, run.adb.commands.count { it.contains("rm -f files/agent-scripts/check.json") })
    }

    @Test
    fun aScriptThatKestrelRefusesStopsTheCommandWithTheFileAndTheReason() {
        var checks = 0
        run.adb.hookWith("AGENT_SCRIPT") {
            if (++checks == 2) 1 to "error: the script is refused (InvalidScript): Reply 2 of the script has keys that the scripted model does not know: require."
            else -1 to "script=valid replies=2"
        }

        assertEquals(
            "Kestrel's scripted model does not take the script wifi-settings.json (error: the script is refused (InvalidScript): Reply 2 of the script has keys " +
                "that the scripted model does not know: require.); fix the script in dev/kestrel/eval/scripts before the runs.",
            assertThrows(Refusal::class.java) { check.check(listOf("alarm.json", "wifi-settings.json")) }.message,
        )
    }

    // A build from before the check installs the script instead: the check removes the scripted model again and refuses it.
    @Test
    fun aBuildWithoutTheCheckIsRefusedAndItsInstalledScriptRemoved() {
        run.adb.hook("AGENT_SCRIPT") { extras -> if (extras["clear"] == "true") "script=none" else "script=installed replies=2" }

        assertThrows(Refusal::class.java) { check.check(listOf("alarm.json")) }

        assertEquals(listOf(mapOf("file" to "check.json", "check" to "true"), mapOf("clear" to "true")), run.adb.hookCalls("AGENT_SCRIPT"))
    }
}
