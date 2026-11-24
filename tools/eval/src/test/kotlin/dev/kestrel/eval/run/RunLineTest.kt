package dev.kestrel.eval.run

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.FakeClock
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.report.ResultsFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/** The run line is task-check.ps1's, which scripts\a71-agent.ps1 reads, and the results file is Add-SessionResult's (design D6 of sub-project 6). */
class RunLineTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** The pattern of a71-agent.ps1's ConvertFrom-TaskLine for the last tab field of a results line. */
    private val taskLine = Regex("^task=\\S+ run=\\d+ ")

    @Test
    fun theLineIsTaskCheckPs1sWithOnlyTheHooksNumbers() {
        val answer = HookAnswer(
            "state=done end=FINISHED steps=5 workMs=41000 credits=0.50 confirmed=1 unconfirmed=0 shareItems=0 recipient=match marker=0 " +
                "beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0",
        )

        assertEquals(
            "task=stand-in-messenger-late run=2 result=pass end=FINISHED check=pass steps=5 workMs=41000 credits=0.50 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0 reason=ok",
            RunLine("stand-in-messenger-late", 2, RunResult.PASS, answer, CheckResult.PASS, Reason.OK).text(),
        )
        assertEquals(
            "task=read-email run=1 result=skipped end=none check=skipped steps=0 workMs=0 credits=0.00 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=untestable",
            RunLine("read-email", 1, RunResult.SKIPPED, null, CheckResult.SKIPPED, Reason.UNTESTABLE).text(),
        )
        assertEquals(
            "task=hotspot run=3 result=fail end=none check=skipped steps=0 workMs=0 credits=unknown model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=stopped",
            RunLine("hotspot", 3, RunResult.FAIL, null, CheckResult.SKIPPED, Reason.STOPPED, "unknown").text(),
        )
    }

    @Test
    fun aResultsLineIsReadBackAsA71AgentPs1ReadsIt() {
        val clock: Clock = FakeClock()
        val file = folder.root.toPath().resolve("owner").resolve("agent-sitting2.txt")
        val writer = LineWriter(FakeConsole(), ResultsFile(file, clock))

        writer.writeOnce("hotspot", 1, RunLine("hotspot", 1, RunResult.PASS, HookAnswer("state=done end=FINISHED credits=1.25"), CheckResult.PASS, Reason.OK), RunDetail.empty("hotspot", 1))

        val line = Files.readAllLines(file).single()
        assertEquals("2026-11-23 12:00:00\ttask\ttask=hotspot run=1 result=pass end=FINISHED check=pass steps=0 workMs=0 credits=1.25 model=none in=0 out=0 cacheRead=0 cacheWrite=0 reason=ok", line)
        assertTrue(taskLine.containsMatchIn(line.split('\t').last()))
        assertTrue(writer.wrote("hotspot", 1))
        val bytes = Files.readAllBytes(file)
        assertTrue(bytes[0] == '2'.code.toByte() && bytes.last() == '\n'.code.toByte())
    }
}
