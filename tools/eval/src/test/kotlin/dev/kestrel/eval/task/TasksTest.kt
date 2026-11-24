package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.run.ScriptInstaller
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The catalogue's rules (design D4 of sub-project 6): the compiler checks each task, and these tests check them together. */
class TasksTest {
    @Test
    fun namesAreUniqueAndEveryTaskHasAChecksAndADryRunOrSaysWhyNot() {
        assertEquals(Catalogue.ALL.size, Catalogue.ALL.map { it.name }.toSet().size)
        for (task in Catalogue.ALL) {
            assertTrue(task.name, task.check.isNotEmpty())
            assertTrue(task.name, task.devices.isNotEmpty())
        }
        // Waze has no stand-in: a dry run skips it.
        assertEquals(listOf("waze-home"), Catalogue.ALL.filter { it.dryRun == null }.map { it.name })
    }

    @Test
    fun everyScriptIsAResourceWithAJsonArrayAndEveryStandInExists() {
        for (task in Catalogue.ALL) {
            val dryRun = task.dryRun ?: continue
            val values = RunValues("maple heron", "Ana", "6:45", "+1 202 555 0160", "pera.test@example.com", "2026-11-24", "6", "45")
            assertTrue(task.name, Json.parseToJsonElement(values.fill(ScriptInstaller.resource(dryRun.script), json = true)).jsonArray.isNotEmpty())
            dryRun.standIn?.let { standIn ->
                val executed = Catalogue.find(standIn)
                assertTrue(task.name, executed != null && executed.kind == TaskKind.STAND_IN && executed.dryRun?.standIn == null)
            }
        }
    }

    @Test
    fun aRehearsalWithStepsChecksOnlyWhatItsStepsMake() {
        for (task in Catalogue.ALL) {
            if (task.rehearsal is Rehearsal.Steps) assertTrue(task.name, task.check.none { it.needsTask })
        }
    }

    @Test
    fun theDevicesAndTheKnownGapsAreTheDesigns() {
        fun devices(name: String) = Catalogue.find(name)!!.devices
        assertEquals(setOf(DeviceKind.A71), devices("telegram-late"))
        assertEquals(setOf(DeviceKind.A71), devices("telegram-photos"))
        assertEquals(setOf(DeviceKind.A71), devices("waze-home"))
        assertEquals(setOf(DeviceKind.EMULATOR), devices("hotspot"))
        assertEquals(setOf(DeviceKind.EMULATOR, DeviceKind.A71), devices("firefox-wiki"))
        assertEquals(setOf(DeviceKind.EMULATOR, DeviceKind.A71), devices("read-email"))
        assertTrue(Catalogue.ALL.filter { it.kind == TaskKind.STAND_IN }.all { it.devices == setOf(DeviceKind.EMULATOR) })
        assertEquals(listOf("telegram-photos", "stand-in-messenger-photos"), Catalogue.ALL.filter { it.gap != null }.map { it.name })
        assertTrue(Catalogue.ALL.filter { it.gap != null }.all { it.runsAtMost == 1 && it.verdict == VerdictRule.KnownGap })
        // Typing a text before the send asks first in Standard mode, so the Telegram tasks and their stand-ins confirm both, and no task confirms a call.
        for (name in listOf("telegram-late", "telegram-photos", "stand-in-messenger-late", "stand-in-messenger-photos")) {
            assertEquals(name, setOf("typing_screen_text", "sending"), Catalogue.find(name)!!.answers.confirm)
        }
        assertTrue(Catalogue.ALL.none { "calls" in it.answers.confirm })
    }

    @Test
    fun theSuitesAreTheSuiteAndD3TasksLiveAndTheScriptsAndStandInsWithoutAModel() {
        val suite = (SettingsTasks.ALL + ClockTasks.ALL + PeopleTasks.ALL + MessageTasks.ALL).map { it.name }
        assertEquals(suite + listOf("hotspot", "firefox-wiki", "read-email"), Catalogue.suite(DeviceKind.EMULATOR, live = true).map { it.name })
        assertEquals(suite - "sms-read" + listOf("telegram-late", "waze-home", "firefox-wiki", "telegram-photos", "read-email"), Catalogue.suite(DeviceKind.A71, live = true).map { it.name })
        assertEquals(
            suite + listOf("stand-in-messenger-late", "stand-in-messenger-late-reworded", "stand-in-messenger-photos", "stand-in-hotspot", "stand-in-firefox", "stand-in-read"),
            Catalogue.suite(DeviceKind.EMULATOR, live = false).map { it.name },
        )
        assertEquals(emptyList<String>(), Catalogue.suite(DeviceKind.A71, live = false).map { it.name })
    }
}
