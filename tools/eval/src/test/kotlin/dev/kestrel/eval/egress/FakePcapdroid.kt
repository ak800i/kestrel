package dev.kestrel.eval.egress

import dev.kestrel.eval.device.FakeAdb
import java.nio.file.Files
import java.nio.file.Paths

/**
 * PCAPdroid on [adb] for a live series' capture: set up, its capture service run by its control, its file growing twice
 * and then pulled as [pcap], and Kestrel with the UID [uid]. Returns whether the capture runs.
 */
fun fakePcapdroid(adb: FakeAdb, uid: Int, pcap: () -> ByteArray): () -> Boolean {
    var running = false
    var size = 1000
    adb.on("^pm list packages com\\.emanuelef\\.remote_capture$") { "package:com.emanuelef.remote_capture" }
    adb.on("^am start -n com\\.emanuelef\\.remote_capture/\\.activities\\.CaptureCtrl -e action (start|stop) ") {
        running = it.groupValues[1] == "start"
        ""
    }
    adb.on("^dumpsys activity services com\\.emanuelef\\.remote_capture$") { if (running) "  * ServiceRecord{1a2b u0 com.emanuelef.remote_capture/.CaptureService}" else "" }
    adb.on("^stat -c %s /sdcard/Download/PCAPdroid/kestrel-") { "$size".also { if (size < 3000) size += 1000 } }
    adb.on("^dumpsys package dev\\.kestrel$") { "    userId=$uid" }
    adb.hook("NETWORK_ALLOWLIST") { "hosts=api.githubcopilot.com modelHosts=*.hf.co" }
    adb.hostOut = { args ->
        if (args.first() == "pull") {
            Files.write(Paths.get(args[2]), pcap())
            "1 file pulled"
        } else {
            ""
        }
    }
    return { running }
}
