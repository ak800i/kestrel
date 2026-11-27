package dev.kestrel.eval.report

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** `eval\baseline.json`: a part per device, each with its own commit and model, and nothing else (design D10 of sub-project 6). */
class BaselineTest {
    @Test
    fun aPartComesFromALiveReportAndReplacesOnlyItsDevicesPart() {
        val report = Report(
            "a71", "c".repeat(40), "claude-x", true, "2026-11-24T10:00", "2026-11-24T12:00",
            listOf(
                TaskReport("dark-mode", 3, 3, emptyMap(), 5.0, 1000.0, 0.3, 0, Tokens()),
                TaskReport("waze-home", 0, 0, mapOf("untestable" to 3), 0.0, 0.0, 0.0, 0, Tokens()),
            ),
            Totals(3, 3, 3, 0.3, 0, Tokens()),
        )
        val part = Baseline.part(report)
        assertEquals(BaselinePart("c".repeat(40), "claude-x", mapOf("dark-mode" to BaselineTask(3, 3))), part)

        val folder = Files.createTempDirectory("eval-baseline")
        val file = folder.resolve("eval").resolve("baseline.json")
        Baseline(mapOf("kestrel-api37" to BaselinePart("a".repeat(40), "m1", mapOf("timer" to BaselineTask(2, 3))))).write(file)
        Baseline.read(file).with("a71", part).write(file)

        val read = Baseline.read(file)
        assertEquals(setOf("kestrel-api37", "a71"), read.devices.keys)
        assertEquals(BaselineTask(2, 3), read.devices.getValue("kestrel-api37").tasks["timer"])
        assertEquals(part, read.devices["a71"])
        val json = Json.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(setOf("devices"), json.keys)
        assertEquals(setOf("commit", "model", "tasks"), json.getValue("devices").jsonObject.getValue("a71").jsonObject.keys)
        assertEquals(Baseline(), Baseline.read(folder.resolve("none.json")))
    }

    @Test
    fun aPartNeedsTheSuitesCommitAndModel() {
        val report = Report("a71", "c".repeat(40), "claude-x", true, "2026-11-24T10:00", "2026-11-24T12:00", emptyList(), Totals(0, 0, 0, 0.0, 0, Tokens()))
        assertNull(Baseline.problem(report))
        assertTrue(Baseline.problem(report.copy(commit = "unknown"))!!.contains("commit"))
        assertTrue(Baseline.problem(report.copy(model = "none"))!!.contains("model"))
        assertThrows(IllegalArgumentException::class.java) { Baseline.part(report.copy(model = "none")) }
    }

    // The main spec's 16.5: the multi-turn tasks' parts are kept apart from the suite's, and neither replaces the other.
    @Test
    fun theMultiTurnPartsAreKeptApartFromTheSuitesParts() {
        val file = Files.createTempDirectory("eval-baseline").resolve("baseline.json")
        val suite = BaselinePart("a".repeat(40), "m1", mapOf("timer" to BaselineTask(2, 3)))
        val multiTurn = BaselinePart("c".repeat(40), "m1", mapOf("followup-settings" to BaselineTask(3, 3)))

        Baseline().with("a71", suite).write(file)
        Baseline.read(file).withMultiTurn("a71", multiTurn).write(file)
        Baseline.read(file).with("kestrel-api37", suite).write(file)

        val read = Baseline.read(file)
        assertEquals(mapOf("a71" to suite, "kestrel-api37" to suite), read.devices)
        assertEquals(mapOf("a71" to multiTurn), read.multiTurn)
        assertEquals(setOf("devices", "multiTurn"), Json.parseToJsonElement(Files.readString(file)).jsonObject.keys)
    }
}
