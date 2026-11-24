package dev.kestrel.eval.egress

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.run.Restores
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * PCAPdroid's capture of Kestrel's traffic during a series (design D11 of sub-project 6). [start] starts it before the
 * first run with the device's key, after the restores that stop it and delete its file. [finish] stops it, waits until
 * its file stops growing, because PCAPdroid writes it after the stop, pulls it into the results folder, deletes it on
 * the device, reads the device's allowlist (at the start and now) and Kestrel's UID, and judges it into `egress.txt`.
 * Without a model (`expectNone`), any connection fails. The tool tells a running capture from PCAPdroid's service,
 * because PCAPdroid's status cannot answer adb. The start asks for `ip_mode both`, because PCAPdroid 2.0.4 captures
 * only IPv4 by default, which would miss Kestrel's IPv6 connections.
 */
class Capture(
    private val device: Device,
    private val hooks: Hooks,
    private val restores: Restores,
    private val clock: Clock,
    private val keys: PcapdroidKeys,
) {
    private var key: String? = null
    private var remote: String? = null
    private val allowlists = mutableListOf<HookAnswer>()

    fun start(stamp: String) {
        val key = keys.get(device.serial)
        if (key == null || !Dumps.packageListed(device.read("pm list packages ${Pcapdroid.PACKAGE}"), Pcapdroid.PACKAGE)) {
            throw Refusal("PCAPdroid is not set up on ${device.serial}; run scripts\\eval.ps1 egress-setup --device ${device.serial} first.")
        }
        val file = "kestrel-$stamp.pcap"
        val remote = "$FOLDER/$file"
        restores.push("PCAPdroid's capture file deleted", "Run: adb -s ${device.serial} shell rm -f $remote") { device.idempotent("rm -f $remote") }
        restores.push("PCAPdroid's capture stopped", "Open PCAPdroid and stop its capture.") { if (EgressSetup.running(device)) EgressSetup.control(device, key, "stop") }
        allowlists.add(hooks.networkAllowlist())
        restores.change("PCAPdroid's capture started", "Open PCAPdroid and stop its capture.") {
            EgressSetup.control(
                device, key, "start",
                " -e pcap_dump_mode pcap_file -e pcap_name $file -e app_filter dev.kestrel -e dump_extensions true -e ip_mode both -e auto_block_private_dns true",
            )
        }
        if (!waitFor(true)) throw IllegalStateException("PCAPdroid's capture did not start on ${device.serial}.")
        this.key = key
        this.remote = remote
    }

    fun finish(folder: Path, expectNone: Boolean): EgressResult {
        val key = checkNotNull(key) { "The capture did not start." }
        val remote = checkNotNull(remote) { "The capture did not start." }
        EgressSetup.control(device, key, "stop")
        if (!waitFor(false)) throw IllegalStateException("PCAPdroid's capture did not stop on ${device.serial}.")
        waitStill(remote)
        val local = folder.resolve("capture.pcap")
        device.pull(remote, local)
        device.idempotent("rm -f $remote")
        allowlists.add(hooks.networkAllowlist())
        val uid = Dumps.userId(device.read("dumpsys package dev.kestrel")) ?: throw IllegalStateException("Kestrel's UID is not known on ${device.serial}.")
        val judged = EgressCheck.judge(PcapReader.read(Files.readAllBytes(local)), uid, names("hosts"), names("modelHosts"))
        val result = if (expectNone && judged.connections.isNotEmpty()) {
            judged.copy(failures = judged.failures + "a run without a model made ${judged.connections.size} connection(s)")
        } else {
            judged
        }
        Files.writeString(folder.resolve("egress.txt"), result.text())
        return result
    }

    private fun names(field: String): List<String> = allowlists.flatMap { it[field].orEmpty().split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun waitFor(state: Boolean): Boolean {
        repeat(10) {
            if (EgressSetup.running(device) == state) return true
            clock.sleep(1.seconds)
        }
        return false
    }

    /** Waits until the file's size stays the same for 2 s, at most 30 s. */
    private fun waitStill(remote: String) {
        var last = -1L
        repeat(15) {
            val size = device.read("stat -c %s $remote").joinToString("").trim().toLongOrNull() ?: -1L
            if (size > 0 && size == last) return
            last = size
            clock.sleep(2.seconds)
        }
        throw IllegalStateException("PCAPdroid's capture file did not stop growing on ${device.serial}.")
    }

    companion object {
        const val FOLDER = "/sdcard/Download/PCAPdroid"
    }
}
