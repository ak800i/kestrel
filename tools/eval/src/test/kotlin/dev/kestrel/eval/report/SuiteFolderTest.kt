package dev.kestrel.eval.report

import dev.kestrel.eval.run.Reason
import dev.kestrel.eval.run.RunLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** A results folder that `suite --resume` reads and continues. */
class SuiteFolderTest {
    private val folder = Files.createTempDirectory("eval-folder").resolve("a71-20261124-110919")

    private fun header(task: String, runs: Int) = "2026-11-24 11:00:00\tsuite\t$task on the A71, $runs runs"

    private fun line(task: String, run: Int, result: String = "pass", reason: String = "ok", model: String = "m1") =
        "2026-11-24 11:00:01\ttask\ttask=$task run=$run result=$result end=FINISHED check=pass steps=3 workMs=9000 credits=0.05 model=$model in=1 out=2 cacheRead=3 cacheWrite=4 reason=$reason"

    private fun write(vararg lines: String) {
        Files.createDirectories(folder)
        Files.write(folder.resolve("runs.txt"), lines.toList())
    }

    @Test
    fun aRunLineIsReadBackAsItWasWritten() {
        val text = line("dnd", 2, "fail", "end").substringAfterLast('\t')
        val parsed = RunLine.parse(text)!!

        assertEquals(text, parsed.text())
        assertEquals(Reason.END, parsed.reason)
        assertEquals("m1", parsed.answer!!["model"])
        assertNull(RunLine.parse("dnd on the A71, 3 runs"))
    }

    @Test
    fun aTaskIsDoneWhenEachOfItsRunsHasALineThatIsNotStopped() {
        write(
            header("dark-mode", 3), line("dark-mode", 1), line("dark-mode", 2, "fail", "check"), line("dark-mode", 3, "skipped", "untestable"),
            header("dnd", 3), line("dnd", 1, "fail", "stopped"), line("dnd", 2), line("dnd", 3),
            header("timer", 2),
            header("calendar-add", 3), line("calendar-add", 1), line("calendar-add", 2, "fail", "end"), line("calendar-add", 3, "fail", "stopped"),
        )

        val read = SuiteFolder.read(folder)
        assertEquals(listOf("dark-mode", "dnd", "timer", "calendar-add"), read.blocks.map { it.task })
        assertEquals(listOf(3, 0, 0, 2), read.blocks.map { it.done })
        assertEquals(listOf(true, false, false, false), read.blocks.map { it.complete })
        assertEquals("2026-11-24T11:00", read.start.toString())
        assertEquals(setOf("m1"), read.models)
    }

    @Test
    fun aResumeKeepsTheDoneTasksContinuesTheLastTaskThatWasCutShortAndRunsTheOthersWhole() {
        write(
            header("dark-mode", 3), line("dark-mode", 1), line("dark-mode", 2), line("dark-mode", 3),
            // Run 1 stopped and the later runs ended: the task runs again whole.
            header("dnd", 3), line("dnd", 1, "fail", "stopped"), line("dnd", 2), line("dnd", 3),
            header("battery-level", 3), line("battery-level", 1),
            header("calendar-add", 3), line("calendar-add", 1), line("calendar-add", 2, "fail", "end"), line("calendar-add", 3, "fail", "stopped"),
        )
        val read = SuiteFolder.read(folder)
        val tasks = listOf("dark-mode" to 3, "dnd" to 3, "battery-level" to 3, "calendar-add" to 3, "alarm" to 3, "telegram-photos" to 1)

        val resume = read.resume(tasks)
        assertEquals(listOf("calendar-add" to 3, "dnd" to 1, "battery-level" to 1, "alarm" to 1, "telegram-photos" to 1), resume.runs)
        assertEquals(listOf("dark-mode", "calendar-add"), resume.kept.map { it.task })
        assertEquals(listOf(1, 2), resume.kept.last().runs.map { it.line.run })

        read.rewrite(resume.kept, "20261124-120000")
        assertEquals(
            listOf(header("dark-mode", 3), line("dark-mode", 1), line("dark-mode", 2), line("dark-mode", 3), header("calendar-add", 3), line("calendar-add", 1), line("calendar-add", 2, "fail", "end")),
            Files.readAllLines(folder.resolve("runs.txt")),
        )
        assertEquals(14, Files.readAllLines(folder.resolve("runs-before-resume-20261124-120000.txt")).size)
    }

    @Test
    fun aTaskWhoseRunsNowDifferOrThatIsNotTheLastIsRunWhole() {
        write(
            header("dark-mode", 3), line("dark-mode", 1),
            header("dnd", 2), line("dnd", 1), line("dnd", 2),
        )

        val resume = SuiteFolder.read(folder).resume(listOf("dark-mode" to 3, "dnd" to 3))
        assertEquals(listOf("dark-mode" to 1, "dnd" to 1), resume.runs)
        assertTrue(resume.kept.isEmpty())
    }

    @Test
    fun aSuitesCallIsKeptInItsFolder() {
        val call = SuiteCall("a71", live = true, runs = 3, tasks = listOf("dark-mode", "dnd"), commit = "c".repeat(40))
        call.write(folder)

        assertEquals(call, SuiteCall.read(folder))
        assertNull(SuiteCall.read(folder.resolveSibling("other")))
    }

    @Test
    fun aFolderFromBeforeSuiteJsonGivesItsDeviceLiveRunsAndTheCommitOfItsTime() {
        write(header("telegram-photos", 1), line("telegram-photos", 1, model = "gpt-6-luna"), header("dnd", 3), line("dnd", 1, "skipped", "untestable", "none"))
        val read = SuiteFolder.read(folder)

        assertEquals("a71", read.device)
        assertEquals(true, read.live)
        assertEquals(3, read.runs)
        write(header("dnd", 3), line("dnd", 1, model = "scripted-light"))
        assertEquals(false, SuiteFolder.read(folder).live)
        write(header("dnd", 3), line("dnd", 1, "skipped", "untestable", "none"))
        assertNull(SuiteFolder.read(folder).live)
        assertNull(SuiteFolder.read(folder.resolveSibling("a71-injection-20261124-110919").also { Files.createDirectories(it); Files.write(it.resolve("runs.txt"), emptyList<String>()) }).device)

        val log = "${"b".repeat(40)}\tHEAD@{20261124-113259}\n${"a".repeat(40)}\tHEAD@{20261124-110733}\n${"9".repeat(40)}\tHEAD@{20261124-090000}\n"
        assertEquals("a".repeat(40), Reflog.headAt(log, "20261124-110919"))
        assertEquals("a".repeat(40), Reflog.headAt(log, "20261124-110733"))
        assertNull(Reflog.headAt(log, "20261124-080000"))
    }
}
