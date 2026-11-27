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
            for (script in listOf(dryRun.script) + dryRun.restarts) {
                assertTrue(task.name, Json.parseToJsonElement(values.fill(ScriptInstaller.resource(script), json = true)).jsonArray.isNotEmpty())
            }
            // A multi-turn task's stand-in is a multi-turn task too, so that it runs apart from the suite as well.
            val standInKind = if (task.kind == TaskKind.CONVERSATION) TaskKind.CONVERSATION else TaskKind.STAND_IN
            dryRun.standIn?.let { standIn ->
                val executed = Catalogue.find(standIn)
                assertTrue(task.name, executed != null && executed.kind == standInKind && executed.dryRun?.standIn == null)
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
        assertEquals(listOf("email-self", "telegram-photos", "stand-in-messenger-photos"), Catalogue.ALL.filter { it.gap != null }.map { it.name })
        assertTrue(Catalogue.ALL.filter { it.gap != null }.all { it.runsAtMost == 1 && it.verdict == VerdictRule.KnownGap })
        // Typing a text before the send asks first in Standard mode, so the Telegram tasks and their stand-ins confirm both, and no task confirms a call.
        for (name in listOf("telegram-late", "telegram-photos", "stand-in-messenger-late", "stand-in-messenger-photos")) {
            assertEquals(name, setOf("typing_screen_text", "sending"), Catalogue.find(name)!!.answers.confirm)
        }
        assertTrue(Catalogue.ALL.none { "calls" in it.answers.confirm })
    }

    // Design 15.3 of sub-project 7 and the main spec's 16.5: the multi-turn tasks run apart from the suite, live on the
    // devices that they name, and with their scripts on kestrel-api37 only.
    @Test
    fun theMultiTurnTasksRunApartFromTheSuite() {
        assertTrue(listOf(true, false).all { live -> Catalogue.suite(DeviceKind.EMULATOR, live).none { it.kind == TaskKind.CONVERSATION } })
        assertEquals(listOf("followup-settings", "quiet-time-ends", "search-past", "continue-past"), Catalogue.multiTurn(DeviceKind.EMULATOR, live = true).map { it.name })
        assertEquals(listOf("followup-settings", "quiet-time-ends", "typed-contact-then-message"), Catalogue.multiTurn(DeviceKind.A71, live = true).map { it.name })
        assertEquals(
            listOf("followup-settings", "quiet-time-ends", "stand-in-typed-contact-then-message", "search-past", "continue-past"),
            Catalogue.multiTurn(DeviceKind.EMULATOR, live = false).map { it.name },
        )
        assertEquals(emptyList<String>(), Catalogue.multiTurn(DeviceKind.A71, live = false).map { it.name })
        assertEquals(listOf("stand-in-typed-contact-then-message"), Catalogue.ALL.filter { !it.runsLive }.map { it.name })
    }

    @Test
    fun theSuitesAreTheSuiteAndD3TasksLiveAndTheScriptsAndStandInsWithoutAModel() {
        val suite = (SettingsTasks.ALL + ClockTasks.ALL + PeopleTasks.ALL + MessageTasks.ALL + AppTasks.ALL).map { it.name }
        // Design D8: kestrel-api37 runs 26 tasks live and the A71 27; email-self is a known gap on both, and telegram-photos on the A71.
        assertEquals(23, suite.size)
        assertEquals(26, Catalogue.suite(DeviceKind.EMULATOR, live = true).size)
        assertEquals(27, Catalogue.suite(DeviceKind.A71, live = true).size)
        assertEquals(suite + listOf("hotspot", "firefox-wiki", "read-email"), Catalogue.suite(DeviceKind.EMULATOR, live = true).map { it.name })
        assertEquals(suite - "sms-read" + listOf("telegram-late", "waze-home", "firefox-wiki", "telegram-photos", "read-email"), Catalogue.suite(DeviceKind.A71, live = true).map { it.name })
        assertEquals(
            suite + listOf("stand-in-messenger-late", "stand-in-messenger-late-reworded", "stand-in-messenger-photos", "stand-in-hotspot", "stand-in-firefox", "stand-in-read"),
            Catalogue.suite(DeviceKind.EMULATOR, live = false).map { it.name },
        )
        assertEquals(emptyList<String>(), Catalogue.suite(DeviceKind.A71, live = false).map { it.name })
    }
}
