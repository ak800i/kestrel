package dev.kestrel.eval.egress

import dev.kestrel.eval.Cli
import dev.kestrel.eval.command.FakeEnvironment
import dev.kestrel.eval.device.AdbException
import dev.kestrel.eval.device.Dumps
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

    /**
     * PCAPdroid on a [FakeRun]'s [adb]: set up, its capture service run by its control, its file growing twice and then
     * pulled as [pcap], and Kestrel's package with the UID [kestrel].
     */
    private fun pcapdroid(adb: FakeAdb, pcap: () -> ByteArray): () -> Boolean = fakePcapdroid(adb, pcap).also {
        adb.on("^dumpsys package dev\\.kestrel$") { "  Package [dev.kestrel] (316e119):\n    userId=$kestrel" }
    }

    @Test
    fun theCaptureRunsDuringTheSeriesAndIsPulledDeletedAndJudged() {
        val run = FakeRun()
        val running = pcapdroid(run.adb) { PcapBuilder().apply { tcp("10.215.173.1", 40002, "140.82.112.3", 443, PcapBuilder.clientHello("api.githubcopilot.com"), kestrel) }.bytes() }
        val keys = PcapdroidKeys(folder.resolve("keys.json")).apply { put("emulator-5554", KEY) }
        val capture = Capture(run.device, run.hooks, run.restores, run.clock, keys)

        capture.start("20261124-100000", folder, expectNone = false)
        assertTrue(running())
        assertTrue(
            run.adb.commands.contains(
                "am start -n com.emanuelef.remote_capture/.activities.CaptureCtrl -e action start -e api_key $KEY -e pcap_dump_mode pcap_file " +
                    "-e pcap_name kestrel-20261124-100000.pcap -e app_filter dev.kestrel -e dump_extensions true -e ip_mode both -e auto_block_private_dns true",
            ),
        )
        // The control brought PCAPdroid to the front, which the first run would otherwise see as its first screen.
        assertEquals("input keyevent KEYCODE_HOME", run.adb.commands.last())
        val result = capture.finish()

        assertTrue(result.failures.toString(), result.passed)
        assertFalse(running())
        assertTrue(Files.readString(folder.resolve("egress.txt")).startsWith("host api.githubcopilot.com connections=1 "))
        assertTrue(Files.isRegularFile(folder.resolve("capture-20261124-100000.pcap")))
        assertTrue(run.adb.commands.contains("rm -f /sdcard/Download/PCAPdroid/kestrel-20261124-100000.pcap"))
        assertTrue(run.restores.runAll().isEmpty())
        // Android 17 names the UID appId= in dumpsys package.
        assertEquals(10350, Dumps.userId(listOf("    installerPackageUid=-1", "    appId=10350")))

        val dry = FakeRun()
        pcapdroid(dry.adb) { PcapBuilder().apply { tcp("10.215.173.1", 40002, "140.82.112.3", 443, PcapBuilder.clientHello("api.githubcopilot.com"), kestrel) }.bytes() }
        val withoutModel = Capture(dry.device, dry.hooks, dry.restores, dry.clock, keys).apply { start("20261124-110000", Files.createTempDirectory("eval-dry"), expectNone = true) }.finish()
        assertEquals(listOf("a run without a model made 1 connection(s)"), withoutModel.failures)
        assertThrows(Refusal::class.java) { Capture(dry.device, dry.hooks, dry.restores, dry.clock, PcapdroidKeys(folder.resolve("none.json"))).start("x", folder, expectNone = false) }
    }

    @Test
    fun aSeriesThatEndsEarlyStillStopsPullsAndJudgesItsCaptureAndAStartThatHungIsStoppedToo() {
        val run = FakeRun()
        val running = pcapdroid(run.adb) { PcapBuilder().apply { tcp("10.215.173.1", 40002, "140.82.112.3", 443, PcapBuilder.clientHello("api.githubcopilot.com"), kestrel) }.bytes() }
        val pull = run.adb.hostOut
        var pulls = 0
        run.adb.hostOut = { args -> if (args.first() == "pull" && pulls++ == 0) throw AdbException("device offline") else pull(args) }
        val keys = PcapdroidKeys(folder.resolve("keys.json")).apply { put("emulator-5554", KEY) }

        Capture(run.device, run.hooks, run.restores, run.clock, keys).start("20261124-120000", folder, expectNone = false)
        // An error or Ctrl+C ends the series here: its restores run without finish().
        assertTrue(run.restores.runAll().isEmpty())

        assertFalse(running())
        assertEquals(2, pulls)
        assertTrue(Files.isRegularFile(folder.resolve("capture-20261124-120000.pcap")))
        assertTrue(Files.readString(folder.resolve("egress.txt")).startsWith("host api.githubcopilot.com connections=1 "))
        assertEquals(EgressSummary(true, 1, 0), EgressParts.summary(EgressParts.read(folder)))
        val stop = run.adb.commands.indexOfLast { it.contains("CaptureCtrl -e action stop") }
        assertTrue(run.adb.commands.indexOfLast { it == "input keyevent KEYCODE_HOME" } > stop)
        assertTrue(run.adb.commands.indexOfLast { it == "rm -f /sdcard/Download/PCAPdroid/kestrel-20261124-120000.pcap" } > stop)

        // PCAPdroid's service has not shown when the start gives up: the restore stops it all the same, and no part is recorded.
        val slow = FakeRun()
        pcapdroid(slow.adb) { PcapBuilder().bytes() }
        slow.adb.on("^dumpsys activity services com\\.emanuelef\\.remote_capture$") { "" }
        val none = Files.createTempDirectory("eval-slow")
        assertThrows(IllegalStateException::class.java) { Capture(slow.device, slow.hooks, slow.restores, slow.clock, keys).start("20261124-130000", none, expectNone = false) }
        assertTrue(slow.restores.runAll().isEmpty())
        assertTrue(slow.adb.commands.any { it.contains("CaptureCtrl -e action stop") })
        assertTrue(slow.adb.commands.contains("rm -f /sdcard/Download/PCAPdroid/kestrel-20261124-130000.pcap"))
        assertTrue(EgressParts.read(none).isEmpty())
    }

    @Test
    fun aCaptureThatCannotBeJudgedFailsTheSuiteWithItsReportAndAResumeKeepsTheResult() {
        val fake = FakeEnvironment()
        fakePcapdroid(fake.adb) { PcapBuilder().bytes() }
        var size = 1000
        fake.adb.on("^stat -c %s /sdcard/Download/PCAPdroid/kestrel-") { "${size++}" }
        PcapdroidKeys(fake.root.resolve("eval").resolve("local").resolve("pcapdroid-keys.json")).put("emulator-5554", KEY)

        assertEquals(Cli.EXIT_FAILED, fake.cli("suite", "--device", "emulator-5554", "--runs", "1", "--tasks", "stand-in-hotspot", "--egress"))

        val results = fake.root.resolve("eval-results").resolve("kestrel-api37-20261123-120000")
        val unknown = EgressSummary(false, 0, 0, unknown = 1)
        assertEquals(unknown, Report.read(results.resolve("report.json")).egress)
        assertTrue(fake.output.contains("task=stand-in-hotspot run=1 result=pass"))
        assertTrue(fake.output.contains("egress=fail connections=0 failures=0 unknown=1"))
        assertTrue(Files.readString(results.resolve("egress.txt")).contains("unknown: the capture could not be judged: PCAPdroid's capture file did not stop growing"))
        assertTrue(fake.adb.commands.contains("rm -f /sdcard/Download/PCAPdroid/kestrel-20261123-120000.pcap"))

        // A resume with no run left keeps the folder's result, never a report without one.
        assertEquals(Cli.EXIT_FAILED, fake.cli("suite", "--device", "emulator-5554", "--resume", results.toString()))
        assertEquals(unknown, Report.read(results.resolve("report.json")).egress)
    }

    @Test
    fun aSuiteWithTheEgressCheckWritesEgressTxtAndTheReportsResult() {
        val fake = FakeEnvironment()
        fakePcapdroid(fake.adb) { PcapBuilder().bytes() }
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
