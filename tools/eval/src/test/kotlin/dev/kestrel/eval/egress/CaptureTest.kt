package dev.kestrel.eval.egress

import dev.kestrel.eval.Cli
import dev.kestrel.eval.command.FakeEnvironment
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.report.EgressSummary
import dev.kestrel.eval.report.Report
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** PCAPdroid's capture during a series, its file and its judgement (design D11 of sub-project 6). */
class CaptureTest {
    private val kestrel = 10234
    private val folder = Files.createTempDirectory("eval-capture")

    /** PCAPdroid on [adb]: set up, its capture service run by its control, its file growing twice and then pulled as [pcap]. */
    private fun pcapdroid(adb: FakeAdb, pcap: () -> ByteArray): () -> Boolean = fakePcapdroid(adb, kestrel, pcap)

    @Test
    fun theCaptureRunsDuringTheSeriesAndIsPulledDeletedAndJudged() {
        val run = FakeRun()
        val running = pcapdroid(run.adb) { PcapBuilder().apply { tcp("10.215.173.1", 40002, "140.82.112.3", 443, PcapBuilder.clientHello("api.githubcopilot.com"), kestrel) }.bytes() }
        val keys = PcapdroidKeys(folder.resolve("keys.json")).apply { put("emulator-5554", KEY) }
        val capture = Capture(run.device, run.hooks, run.restores, run.clock, keys)

        capture.start("20261124-100000")
        assertTrue(running())
        assertTrue(
            run.adb.commands.contains(
                "am start -n com.emanuelef.remote_capture/.activities.CaptureCtrl -e action start -e api_key $KEY -e pcap_dump_mode pcap_file " +
                    "-e pcap_name kestrel-20261124-100000.pcap -e app_filter dev.kestrel -e dump_extensions true -e ip_mode both -e auto_block_private_dns true",
            ),
        )
        val result = capture.finish(folder, expectNone = false)

        assertTrue(result.failures.toString(), result.passed)
        assertFalse(running())
        assertTrue(Files.readString(folder.resolve("egress.txt")).startsWith("host api.githubcopilot.com connections=1 "))
        assertTrue(Files.isRegularFile(folder.resolve("capture.pcap")))
        assertTrue(run.adb.commands.contains("rm -f /sdcard/Download/PCAPdroid/kestrel-20261124-100000.pcap"))
        assertTrue(run.restores.runAll().isEmpty())

        val dry = FakeRun()
        pcapdroid(dry.adb) { PcapBuilder().apply { tcp("10.215.173.1", 40002, "140.82.112.3", 443, PcapBuilder.clientHello("api.githubcopilot.com"), kestrel) }.bytes() }
        val withoutModel = Capture(dry.device, dry.hooks, dry.restores, dry.clock, keys).apply { start("20261124-110000") }.finish(Files.createTempDirectory("eval-dry"), expectNone = true)
        assertEquals(listOf("a run without a model made 1 connection(s)"), withoutModel.failures)
        assertThrows(Refusal::class.java) { Capture(dry.device, dry.hooks, dry.restores, dry.clock, PcapdroidKeys(folder.resolve("none.json"))).start("x") }
    }

    @Test
    fun aSuiteWithTheEgressCheckWritesEgressTxtAndTheReportsResult() {
        val fake = FakeEnvironment()
        pcapdroid(fake.adb) { PcapBuilder().bytes() }
        PcapdroidKeys(fake.root.resolve("eval").resolve("local").resolve("pcapdroid-keys.json")).put("emulator-5554", KEY)

        assertEquals(Cli.EXIT_PASSED, fake.cli("suite", "--device", "emulator-5554", "--runs", "1", "--tasks", "stand-in-hotspot", "--egress"))

        val results = fake.root.resolve("eval-results").resolve("kestrel-api37-20261123-120000")
        assertEquals(EgressSummary(true, 0, 0), Report.read(results.resolve("report.json")).egress)
        assertTrue(fake.output.contains("egress=pass connections=0 failures=0"))
        assertEquals("result pass connections=0 failures=0\n", Files.readString(results.resolve("egress.txt")))
        assertFalse(fake.output.contains(KEY))
    }

    private companion object {
        const val KEY = "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
    }
}
