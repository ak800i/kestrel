package dev.kestrel.eval.command

import dev.kestrel.eval.Cli
import dev.kestrel.eval.report.Baseline
import dev.kestrel.eval.report.BaselinePart
import dev.kestrel.eval.report.BaselineTask
import dev.kestrel.eval.report.Report
import dev.kestrel.eval.report.TaskReport
import dev.kestrel.eval.report.Tokens
import dev.kestrel.eval.report.Totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** `compare` and the suite's baseline options (design D2 and D10 of sub-project 6). */
class CompareCommandTest {
    private val fake = FakeEnvironment()
    private val names = ('a'..'j').map { "$it" }

    private fun report(device: String, model: String, changed: Map<String, Int> = emptyMap(), tasks: List<String> = names): Report {
        val reports = tasks.map { TaskReport(it, changed[it] ?: 3, 3, emptyMap(), 4.0, 9000.0, 0.5, 0, Tokens()) }
        return Report(device, "c".repeat(40), model, true, "2026-11-24T10:00", "2026-11-24T12:00", reports, Totals(reports.sumOf { it.passes }, reports.sumOf { it.runs }, 0, 5.0, 0, Tokens()))
    }

    private fun folder(name: String, report: Report): String {
        val folder = fake.root.resolve("eval-results").resolve(name)
        report.write(folder.resolve("report.json"))
        return folder.toString()
    }

    private fun baseline(vararg devices: String) {
        Baseline(devices.associateWith { BaselinePart("b".repeat(40), "m1", names.associateWith { BaselineTask(3, 3) }) }).write(fake.root.resolve("eval").resolve("baseline.json"))
    }

    @Test
    fun eachDeviceIsJudgedAgainstItsOwnPartAndTwoDevicesGiveTheirTotal() {
        baseline("kestrel-api37", "a71")
        val emulator = folder("kestrel-api37-20261124-100000", report("kestrel-api37", "m1", mapOf("j" to 2)))
        val a71 = folder("a71-20261124-140000", report("a71", "m1"))

        assertEquals(Cli.EXIT_PASSED, fake.cli("compare", emulator, a71))
        assertTrue(fake.output.contains("compare=kestrel-api37 result=pass rate=96.7 baseline=100.0 tasks=10"))
        assertTrue(fake.output.contains("compare=a71 result=pass rate=100.0 baseline=100.0 tasks=10"))
        assertTrue(fake.output.contains("compare=total passes=59 runs=60 credits=10.00"))
        assertEquals(Report.read(fake.root.resolve("eval-results").resolve("a71-20261124-140000").resolve("report.json")), report("a71", "m1"))
    }

    @Test
    fun aFallFailsUnlessTheReRunsBringItBackAndAnotherModelOrNoPartIsRefused() {
        baseline("kestrel-api37")
        val fell = folder("kestrel-api37-20261124-100000", report("kestrel-api37", "m1", mapOf("i" to 2, "j" to 1)))

        assertEquals(Cli.EXIT_FAILED, fake.cli("compare", fell))
        val asked = mutableListOf<Pair<String, List<String>>>()
        val rerun = CompareCommand(fake.environment, Command.Compare(listOf(fell), rerun = true, device = "emulator-5554")) { serial, tasks ->
            asked.add(serial to tasks)
            report("kestrel-api37", "m1", tasks = tasks)
        }
        assertEquals(Cli.EXIT_PASSED, rerun.execute())
        assertEquals(listOf("emulator-5554" to listOf("i", "j")), asked)
        assertTrue(fake.output.contains("compare=kestrel-api37 collapsed=j"))

        val other = folder("kestrel-api37-20261124-110000", report("kestrel-api37", "m2"))
        assertEquals(Cli.EXIT_REFUSED, fake.cli("compare", other))
        assertTrue(fake.output.contains("--record-baseline"))
        assertEquals(Cli.EXIT_REFUSED, fake.cli("compare", folder("a71-20261124-140000", report("a71", "m1"))))
    }

    @Test
    fun theCommandLinesOfCompareAndOfTheBaseline() {
        assertEquals(Command.Compare(listOf("a", "b"), rerun = true, device = "emulator-5554"), Arguments.parse(listOf("compare", "a", "b", "--rerun", "--device", "emulator-5554")))
        assertEquals(Command.Compare(listOf("a")), Arguments.parse(listOf("compare", "a")))
        assertThrows(UsageError::class.java) { Arguments.parse(listOf("compare")) }
        assertThrows(UsageError::class.java) { Arguments.parse(listOf("compare", "a", "--rerun")) }
        assertEquals(
            Command.Suite("emulator-5554", live = true, compare = true, recordBaseline = true),
            Arguments.parse(listOf("suite", "--device", "emulator-5554", "--live", "--compare", "--record-baseline")),
        )
        assertThrows(UsageError::class.java) { Arguments.parse(listOf("suite", "--device", "emulator-5554", "--record-baseline")) }
        assertThrows(UsageError::class.java) { Arguments.parse(listOf("suite", "--device", "emulator-5554", "--live", "--record-baseline", "--tasks", "dnd")) }
        assertThrows(UsageError::class.java) { Arguments.parse(listOf("suite", "--device", "emulator-5554", "--compare")) }
    }
}
