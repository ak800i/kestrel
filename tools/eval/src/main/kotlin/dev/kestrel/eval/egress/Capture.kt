package dev.kestrel.eval.egress

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.report.EgressSummary
import dev.kestrel.eval.run.Restores
import dev.kestrel.eval.run.Stopping
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * PCAPdroid's capture of Kestrel's traffic during one call of a series (design D11 of sub-project 6), judged into a part
 * of the results folder's egress check ([EgressParts]). [start] reads Kestrel's UID and starts the capture before the
 * first run with the device's key, after the restore that puts it back, and leaves the home screen in front, as the
 * first run would find it without a capture. [finish] stops it, waits until its file stops
 * growing, because PCAPdroid writes it after the stop, pulls it into the folder as `capture-<stamp>.pcap`, deletes it on
 * the device, reads the device's allowlist (at the start and now), and judges it. Without a model (`expectNone`), any
 * connection fails. A part that cannot be judged is recorded as unknown, with its reason.
 *
 * When the series ends early (an error, Ctrl+C), the restore judges the capture as [finish] would. Whatever the
 * judgement did, the restore then stops a capture that this call started, even one whose service has not shown yet, and
 * deletes the device's file once PCAPdroid's service has stopped and the file stopped growing. The tool tells a running
 * capture from PCAPdroid's service, because PCAPdroid's status cannot answer adb. The start asks for `ip_mode both`,
 * because PCAPdroid 2.0.4 captures only IPv4 by default, which would miss Kestrel's IPv6 connections.
 */
class Capture(
    private val device: Device,
    private val hooks: Hooks,
    private val restores: Restores,
    private val clock: Clock,
    private val keys: PcapdroidKeys,
) {
    private lateinit var key: String
    private lateinit var remote: String
    private lateinit var folder: Path
    private lateinit var stamp: String
    private var expectNone = false
    private var uid = 0
    private val allowlists = mutableListOf<HookAnswer>()

    // Guarded by this object's lock, which the restore (on the shutdown hook's thread, too) and the main thread share.
    private var capturing = false
    private var judged = false
    private var closed = false
    private var result: EgressResult? = null

    fun start(stamp: String, folder: Path, expectNone: Boolean) {
        val key = keys.get(device.serial)
        if (key == null || !Dumps.packageListed(device.read("pm list packages ${Pcapdroid.PACKAGE}"), Pcapdroid.PACKAGE)) {
            throw Refusal("PCAPdroid is not set up on ${device.serial}; run scripts\\eval.ps1 egress-setup --device ${device.serial} first.")
        }
        uid = Dumps.userId(device.read("dumpsys package dev.kestrel")) ?: throw IllegalStateException("Kestrel's UID is not known on ${device.serial}.")
        val file = "kestrel-$stamp.pcap"
        this.key = key
        this.remote = "$FOLDER/$file"
        this.folder = folder
        this.stamp = stamp
        this.expectNone = expectNone
        restores.push("PCAPdroid's capture stopped, judged and its file deleted", "Open PCAPdroid, stop its capture, and delete Download/PCAPdroid/$file.") { close() }
        allowlists.add(hooks.networkAllowlist())
        restores.change("PCAPdroid's capture started", "Open PCAPdroid and stop its capture.") {
            EgressSetup.control(
                device, key, "start",
                " -e pcap_dump_mode pcap_file -e pcap_name $file -e app_filter dev.kestrel -e dump_extensions true -e ip_mode both -e auto_block_private_dns true",
            )
        }
        if (!waitFor(true)) throw IllegalStateException("PCAPdroid's capture did not start on ${device.serial}.")
        leavePcapdroid()
        synchronized(this) {
            if (closed) throw Stopping("The capture was put back while it started, because :tools:eval is stopping.")
            capturing = true
            EgressParts.put(folder, EgressPart(stamp, reason = "the series ended before its capture was judged"))
        }
    }

    /** Stops the capture and judges it into the folder's part; a capture that cannot be judged throws, after its part says why. */
    fun finish(): EgressResult = synchronized(this) {
        check(capturing) { "The capture did not start." }
        judge()
    }

    private fun judge(): EgressResult {
        result?.let { return it }
        judged = true
        try {
            stop()
            waitStill(strict = true)
            val local = folder.resolve("capture-$stamp.pcap")
            device.pull(remote, local)
            device.idempotent("rm -f $remote")
            allowlists.add(hooks.networkAllowlist())
            val checked = EgressCheck.judge(PcapReader.read(Files.readAllBytes(local)), uid, names("hosts"), names("modelHosts"))
            val outcome = if (expectNone && checked.connections.isNotEmpty()) {
                checked.copy(failures = checked.failures + "a run without a model made ${checked.connections.size} connection(s)")
            } else {
                checked
            }
            EgressParts.put(folder, EgressPart(stamp, EgressSummary(outcome.passed, outcome.connections.size, outcome.failures.size), outcome.text()))
            result = outcome
            return outcome
        } catch (e: Exception) {
            EgressParts.put(folder, EgressPart(stamp, reason = "the capture could not be judged: ${e.message ?: e.javaClass.simpleName}"))
            throw e
        }
    }

    /** The restore: the judgement if the series ended before [finish], then the device put back whatever it did. */
    private fun close() = synchronized(this) {
        closed = true
        if (capturing && !judged) {
            try {
                judge()
            } catch (e: Exception) {
                // The part already says why; the device is put back below.
            }
        }
        if (result == null || EgressSetup.running(device)) {
            stop()
            waitStill(strict = false)
        }
        device.idempotent("rm -f $remote")
    }

    private fun stop() {
        EgressSetup.control(device, key, "stop")
        if (!waitFor(false)) throw IllegalStateException("PCAPdroid's capture did not stop on ${device.serial}.")
        leavePcapdroid()
    }

    /**
     * The home screen in front again: PCAPdroid's control brings its task to the front at a start and at a stop, and after
     * a start, a task without a setup of its own would take PCAPdroid's screen, which lists Kestrel's connections, as its
     * first observation.
     */
    private fun leavePcapdroid() {
        device.idempotent("input keyevent KEYCODE_HOME")
    }

    private fun names(field: String): List<String> = allowlists.flatMap { it[field].orEmpty().split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun waitFor(state: Boolean): Boolean {
        repeat(10) {
            if (EgressSetup.running(device) == state) return true
            clock.sleep(1.seconds)
        }
        return false
    }

    /**
     * Waits until the file's size stays the same for 2 s, at most 30 s. [strict] needs a file that stopped growing; the
     * restore also takes a missing file (-1 twice), and goes on after 30 s, because PCAPdroid's service has stopped then.
     */
    private fun waitStill(strict: Boolean) {
        var last = -2L
        repeat(15) {
            val size = device.read("stat -c %s $remote 2>/dev/null || echo -1").joinToString("").trim().toLongOrNull() ?: -1L
            if (size == last && (size > 0 || !strict)) return
            last = size
            clock.sleep(2.seconds)
        }
        if (strict) throw IllegalStateException("PCAPdroid's capture file did not stop growing on ${device.serial}.")
    }

    companion object {
        const val FOLDER = "/sdcard/Download/PCAPdroid"
    }
}
