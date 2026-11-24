package dev.kestrel.eval.command

import dev.kestrel.eval.Cli
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.egress.PcapBuilder
import dev.kestrel.eval.egress.PcapdroidKeys
import dev.kestrel.eval.egress.fakePcapdroid
import dev.kestrel.eval.report.Baseline
import dev.kestrel.eval.report.EgressSummary
import dev.kestrel.eval.report.KestrelBuild
import dev.kestrel.eval.report.Report
import dev.kestrel.eval.report.SuiteCall
import dev.kestrel.eval.task.Catalogue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** `suite --resume <results folder>`: an interrupted suite goes on in its own folder. */
class ResumeTest {
    private val fake = FakeEnvironment()
    private val commit = FakeEnvironment.COMMIT
    private val pair = listOf("stand-in-hotspot", "stand-in-firefox")
    private val build = KestrelBuild(versionCode = 100, versionName = "1.0.0", lastUpdateTime = "2026-11-24 09:18:59", commit = commit, dirty = false)

    private fun runLines(): List<String> = fake.output.lines().filter { it.startsWith("task=") }

    private fun line(task: String, run: Int, model: String = "scripted", reason: String = "ok") =
        "2026-11-24 10:00:01\ttask\ttask=$task run=$run result=pass end=FINISHED check=pass steps=3 workMs=9000 credits=0.00 model=$model in=0 out=0 cacheRead=0 cacheWrite=0 reason=$reason"

    private fun folder(name: String, call: SuiteCall?, vararg lines: String): Path {
        val folder = fake.root.resolve("eval-results").resolve(name)
        Files.createDirectories(folder)
        call?.write(folder)
        Files.write(folder.resolve("runs.txt"), lines.toList())
        return folder
    }

    /** An emulator suite of [pair] with 2 runs each, whose stand-in-hotspot ended and stand-in-firefox never began. */
    private fun halfDone(callCommit: String = commit, model: String = "scripted"): Path = folder(
        "kestrel-api37-20261124-100000", SuiteCall("kestrel-api37", live = false, runs = 2, tasks = pair, commit = callCommit),
        "2026-11-24 10:00:00\tsuite\tstand-in-hotspot on kestrel-api37, 2 runs", line("stand-in-hotspot", 1, model), line("stand-in-hotspot", 2, model),
    )

    @Test
    fun aSuiteThatStoppedGoesOnInItsFolderWithOnlyTheRunsThatHadNoLine() {
        assertEquals(Cli.EXIT_PASSED, fake.cli("suite", "--device", "emulator-5554", "--runs", "2", "--tasks", pair.joinToString(",")))
        val folder = fake.root.resolve("eval-results").resolve("kestrel-api37-20261123-120000")
        assertEquals(SuiteCall("kestrel-api37", live = false, runs = 2, tasks = pair, commit = commit, kestrel = build), SuiteCall.read(folder))
        // As after Ctrl+C in run 2 of the first task: that run's line says stopped, and the second task never began.
        val runs = Files.readAllLines(folder.resolve("runs.txt"))
        val stopped = runs[2].replace(Regex("result=pass .*"), "result=fail end=none check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=stopped")
        Files.write(folder.resolve("runs.txt"), listOf(runs[0], runs[1], stopped))
        Files.delete(folder.resolve("report.json"))
        val before = runLines().size

        assertEquals(Cli.EXIT_PASSED, fake.cli("suite", "--device", "emulator-5554", "--resume", folder.toString()))

        assertEquals(listOf("task=stand-in-hotspot run=2", "task=stand-in-firefox run=1", "task=stand-in-firefox run=2"), runLines().drop(before).map { it.substringBefore(" result") })
        val after = Files.readAllLines(folder.resolve("runs.txt")).map { it.split('\t').drop(1).joinToString("\t").substringBefore(" result") }
        assertEquals(
            listOf(
                "suite\tstand-in-hotspot on kestrel-api37, 2 runs", "task\ttask=stand-in-hotspot run=1", "task\ttask=stand-in-hotspot run=2",
                "suite\tstand-in-firefox on kestrel-api37, 2 runs", "task\ttask=stand-in-firefox run=1", "task\ttask=stand-in-firefox run=2",
            ),
            after,
        )
        val report = Report.read(folder.resolve("report.json"))
        assertEquals(pair, report.tasks.map { it.task })
        assertEquals(listOf(2, 2), report.tasks.map { it.passes })
        assertEquals(commit, report.commit)
        assertTrue(Files.list(folder).use { files -> files.anyMatch { it.fileName.toString().startsWith("runs-before-resume-") } })

        // A folder whose tasks all ended runs nothing more.
        val ended = runLines().size
        assertEquals(Cli.EXIT_PASSED, fake.cli("suite", "--device", "emulator-5554", "--resume", folder.toString()))
        assertEquals(ended, runLines().size)
    }

    @Test
    fun aResumeIsRefusedForAnotherDeviceOrLiveAndItsCommandLineIsChecked() {
        val a71 = folder("a71-20261124-110919", null, "2026-11-24 10:00:00\tsuite\tdark-mode on the A71, 3 runs", line("dark-mode", 1, "m1"))
        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--live", "--resume", a71.toString()))
        assertTrue(fake.output.contains("the A71"))

        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--live", "--resume", halfDone().toString()))
        assertTrue(fake.output.contains("without --live"))
        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--resume", fake.root.resolve("eval-results").resolve("nothing").toString()))
        assertTrue(fake.adb.hookCalls("VOICE").isEmpty())

        assertEquals(Command.Suite("emulator-5554", live = true, recordBaseline = true, resume = "f"), Arguments.parse(listOf("suite", "--device", "emulator-5554", "--live", "--record-baseline", "--resume", "f")))
        assertThrows(UsageError::class.java) { Arguments.parse(listOf("suite", "--device", "emulator-5554", "--resume", "f", "--tasks", "dnd")) }
        assertThrows(UsageError::class.java) { Arguments.parse(listOf("suite", "--device", "emulator-5554", "--resume", "f", "--runs", "3")) }
    }

    @Test
    fun aCommitThatChangedMoreThanTheToolIsRefusedAndOneThatChangedOnlyTheToolGoesOn() {
        val older = "a".repeat(40)
        fake.gitDiff = "app/src/main/kotlin/dev/kestrel/app/MainActivity.kt\n"
        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--resume", halfDone(older).toString()))
        assertTrue(fake.output.contains("app/src/main/kotlin/dev/kestrel/app/MainActivity.kt"))
        assertTrue(fake.adb.hookCalls("VOICE").isEmpty())

        fake.gitDiff = ""
        assertEquals(Cli.EXIT_PASSED, fake.cli("suite", "--device", "emulator-5554", "--resume", halfDone(older).toString()))
        assertTrue(fake.processes.contains(listOf("git", "-C", fake.root.toString(), "diff", "--name-only", older, commit) + Resume.KESTREL_PATHS))
        assertEquals(commit, SuiteCall.read(fake.root.resolve("eval-results").resolve("kestrel-api37-20261124-100000"))!!.commit)
    }

    @Test
    fun onlyThePathsThatGitattributesMarksNeutralChangeNoKestrel() {
        val repository = TestRepository()
        repository.write("app/src/main/kotlin/A.kt", "1")
        repository.write("eval/baseline.json", "{}")
        repository.write("tools/eval/src/main/kotlin/T.kt", "1")
        repository.write("docs/superpowers/plans/p.md", "1")
        repository.write("README.md", "1")
        val first = repository.commit()
        // As when the first device's part of the baseline is committed before the other device's folder is resumed.
        repository.write("eval/baseline.json", "{\"devices\":{}}")
        repository.write("tools/eval/src/main/kotlin/T.kt", "2")
        repository.write("docs/superpowers/plans/p.md", "2")
        repository.write("core/agent/NOTES.md", "new")
        val neutral = repository.commit()

        assertEquals(emptyList<String>(), Resume.kestrelChanges(repository.environment, first, neutral))

        repository.write("app/src/main/kotlin/A.kt", "2")
        repository.write("eval/other.json", "{}")
        val kestrel = repository.commit()
        assertEquals(listOf("app/src/main/kotlin/A.kt", "eval/other.json"), Resume.kestrelChanges(repository.environment, neutral, kestrel))
    }

    @Test
    fun aResumeAcrossAToolCommitRunsTheCutShortTaskFromItsFirstRunAndNamesTheKeptTasks() {
        val older = "a".repeat(40)
        // The commits differ only in the tool, so git names no path of Kestrel's.
        fake.gitDiff = ""
        val folder = folder(
            "kestrel-api37-20261124-100000", SuiteCall("kestrel-api37", live = false, runs = 2, tasks = pair, commit = older),
            "2026-11-24 10:00:00\tsuite\tstand-in-hotspot on kestrel-api37, 2 runs", line("stand-in-hotspot", 1), line("stand-in-hotspot", 2),
            "2026-11-24 10:00:02\tsuite\tstand-in-firefox on kestrel-api37, 2 runs", line("stand-in-firefox", 1),
        )
        val before = runLines().size

        assertEquals(Cli.EXIT_PASSED, fake.cli("suite", "--device", "emulator-5554", "--resume", folder.toString()))

        assertEquals(listOf("task=stand-in-firefox run=1", "task=stand-in-firefox run=2"), runLines().drop(before).map { it.substringBefore(" result") })
        val after = Files.readAllLines(folder.resolve("runs.txt")).map { it.split('\t').drop(1).joinToString("\t").substringBefore(" result") }
        assertEquals(
            listOf(
                "suite\tstand-in-hotspot on kestrel-api37, 2 runs", "task\ttask=stand-in-hotspot run=1", "task\ttask=stand-in-hotspot run=2",
                "suite\tstand-in-firefox on kestrel-api37, 2 runs", "task\ttask=stand-in-firefox run=1", "task\ttask=stand-in-firefox run=2",
            ),
            after,
        )
        assertTrue(fake.output.contains("stand-in-hotspot ran at $older"))
    }

    @Test
    fun aResumeIsRefusedWhenKestrelWasInstalledAgainSinceTheFoldersRuns() {
        val folder = folder(
            "kestrel-api37-20261124-100000", SuiteCall("kestrel-api37", live = false, runs = 2, tasks = pair, commit = commit, kestrel = build),
            "2026-11-24 10:00:00\tsuite\tstand-in-hotspot on kestrel-api37, 2 runs", line("stand-in-hotspot", 1), line("stand-in-hotspot", 2),
        )
        val kept = Files.readAllLines(folder.resolve("runs.txt"))
        fake.installed = "2026-11-24 12:30:00"

        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--resume", folder.toString()))

        assertTrue(fake.output.contains("Kestrel was installed again since the folder's runs"))
        assertEquals(kept, Files.readAllLines(folder.resolve("runs.txt")))
        assertTrue(fake.adb.hookCalls("VOICE").isEmpty())
    }

    @Test
    fun aFolderFromBeforeSuiteJsonTakesTheCommitThatHeadWasAtItsTime() {
        val legacy = folder("a71-20261124-110919", null, "2026-11-24 10:00:00\tsuite\tdark-mode on the A71, 3 runs", line("dark-mode", 1, "m1"))
        fake.devices["R58N66RVD7Z"] = dev.kestrel.eval.device.FakeAdb(serial = "R58N66RVD7Z", model = "SM-A715F", avd = "", sdk = 33)
        fake.reflog = "$commit\tHEAD@{20261124-113259}\n${"a".repeat(40)}\tHEAD@{20261124-110733}\n"
        fake.gitDiff = "core/agent/src/main/kotlin/dev/kestrel/agent/Agent.kt\n"

        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "R58N66RVD7Z", "--live", "--resume", legacy.toString()))
        assertTrue(fake.processes.contains(listOf("git", "-C", fake.root.toString(), "diff", "--name-only", "a".repeat(40), commit) + Resume.KESTREL_PATHS))
        fake.reflog = ""
        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "R58N66RVD7Z", "--live", "--resume", legacy.toString()))
        assertTrue(fake.output.contains("no commit"))
    }

    @Test
    fun runsOfAnotherModelThanTheFoldersAreTakenOutAgainAndRefused() {
        val folder = halfDone(model = "scripted-light")
        val kept = Files.readAllLines(folder.resolve("runs.txt"))

        assertNotEquals(Cli.EXIT_PASSED, fake.cli("suite", "--device", "emulator-5554", "--resume", folder.toString()))
        assertTrue(fake.output.contains("scripted-light"))
        assertEquals(kept, Files.readAllLines(folder.resolve("runs.txt")))

        val mixed = folder("kestrel-api37-20261124-110000", SuiteCall("kestrel-api37", live = false, runs = 2, tasks = pair, commit = commit),
            "2026-11-24 10:00:00\tsuite\tstand-in-hotspot on kestrel-api37, 2 runs", line("stand-in-hotspot", 1, "m1"), line("stand-in-hotspot", 2, "m2"))
        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--resume", mixed.toString()))
        assertTrue(fake.output.contains("m2"))
    }

    @Test
    fun recordBaselineWithResumeRecordsThePartOfTheWholeFolder() {
        val suite = Catalogue.suite(DeviceKind.EMULATOR, live = true)
        val lines = mutableListOf<String>()
        for (task in suite.filter { it.name != "battery-level" }) {
            val runs = task.runsAtMost?.let { minOf(it, 3) } ?: 3
            lines.add("2026-11-24 10:00:00\tsuite\t${task.name} on kestrel-api37, $runs runs")
            for (run in 1..runs) lines.add(line(task.name, run, "m1"))
        }
        val folder = folder("kestrel-api37-20261124-100000", SuiteCall("kestrel-api37", live = true, runs = 3, tasks = suite.map { it.name }, commit = commit), *lines.toTypedArray())
        fake.end = fake.end.replace("model=scripted", "model=m1")
        val few = folder("kestrel-api37-20261124-090000", SuiteCall("kestrel-api37", live = true, runs = 3, tasks = listOf("dark-mode"), commit = commit), lines[0], lines[1])
        assertEquals(Cli.EXIT_REFUSED, fake.cli("suite", "--device", "emulator-5554", "--live", "--record-baseline", "--resume", few.toString()))
        assertTrue(fake.output.contains("--record-baseline records a suite of every task"))

        // A live suite captures its traffic with PCAPdroid (design D11 of sub-project 6).
        fakePcapdroid(fake.adb) { PcapBuilder().bytes() }
        PcapdroidKeys(fake.root.resolve("eval").resolve("local").resolve("pcapdroid-keys.json")).put("emulator-5554", "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6")
        fake.cli("suite", "--device", "emulator-5554", "--live", "--record-baseline", "--resume", folder.toString())

        assertEquals(3, runLines().count { it.startsWith("task=battery-level ") })
        val report = Report.read(folder.resolve("report.json"))
        // The folder's earlier runs were not captured, so its egress check is unknown, beside the resumed runs' capture.
        assertEquals(EgressSummary(false, 0, 0, unknown = 1), report.egress)
        assertEquals(suite.map { it.name }.toSet(), report.tasks.map { it.task }.toSet())
        val part = Baseline.read(fake.root.resolve("eval").resolve("baseline.json")).devices.getValue("kestrel-api37")
        assertEquals(report.tasks.filter { it.runs > 0 }.map { it.task }.toSet(), part.tasks.keys)
        assertTrue(part.tasks.keys.containsAll(listOf("dark-mode", "read-email")))
        assertEquals(commit, part.commit)
        assertEquals("m1", part.model)
        assertTrue(fake.output.contains("eval\\baseline.json now holds the part of kestrel-api37"))
        assertTrue(fake.output.contains("The egress check of the recorded runs failed (connections=0 failures=0 unknown=1); see egress.txt in $folder."))
    }
}
