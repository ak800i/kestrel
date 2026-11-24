package dev.kestrel.eval.egress

import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** PCAPdroid's pinned download, its keys and its one-time setup, without the owner (design D11 of sub-project 6). */
class EgressSetupTest {
    private val folder = Files.createTempDirectory("eval-egress")

    @Test
    fun theDownloadKeepsOnlyAFileWithItsPinnedSizeAndHash() {
        val bytes = "PCAPdroid".toByteArray()
        Files.write(folder.resolve("sample"), bytes)
        val release = Release("https://example.invalid/sample.apk", "sample.apk", bytes.size.toLong(), ReleaseDownload.sha256(folder.resolve("sample")))
        var fetches = 0

        val file = ReleaseDownload { fetches++; bytes.inputStream() }.get(release, folder.resolve("local"))
        assertEquals(bytes.toList(), Files.readAllBytes(file).toList())
        assertEquals(file, ReleaseDownload { error("no second download") }.get(release, folder.resolve("local")))
        assertEquals(1, fetches)

        val wrong = release.copy(file = "wrong.apk")
        assertThrows(IllegalStateException::class.java) { ReleaseDownload { "PCAPdroiD".byteInputStream() }.get(wrong, folder.resolve("local")) }
        assertFalse(Files.exists(folder.resolve("local").resolve("wrong.apk")))
        assertFalse(Files.exists(folder.resolve("local").resolve("wrong.apk.part")))
        assertEquals("b601798767bb8c989a2ec389e4d21a2a0ab2ae2c2f9292238b4e3ee2f52af811", Pcapdroid.RELEASE.sha256)
        assertEquals(17_641_581L, Pcapdroid.RELEASE.size)
    }

    @Test
    fun keysAreKeptPerSerialAndNeverInTheirText() {
        val keys = PcapdroidKeys(folder.resolve("eval").resolve("local").resolve("pcapdroid-keys.json"))
        assertNull(keys.get("emulator-5554"))
        keys.put("emulator-5554", KEY)
        keys.put("R58N66RVD7Z", KEY.reversed())

        assertEquals(KEY, PcapdroidKeys(folder.resolve("eval").resolve("local").resolve("pcapdroid-keys.json")).get("emulator-5554"))
        assertEquals(KEY.reversed(), keys.get("R58N66RVD7Z"))
        assertFalse(keys.toString().contains(KEY))
    }

    @Test
    fun aNewDeviceIsSetUpByTheScriptsOwnTapsAndTheKeyIsReadFromPcapdroidsOwnNode() {
        val run = FakeRun()
        var installed = false
        var running = false
        var page = 0
        run.adb.on("^pm list packages com\\.emanuelef\\.remote_capture$") { if (installed) "package:com.emanuelef.remote_capture" else "" }
        run.adb.hostOut = { args ->
            when {
                args.first() == "install" -> "Success".also { installed = true }
                args.take(3) == listOf("exec-out", "uiautomator", "dump") ->
                    "<?xml version='1.0'?><hierarchy rotation=\"0\"><node text=\"${KEY.reversed()}\" package=\"com.example.other\" />" +
                        "<node index=\"2\" text=\"$KEY\" resource-id=\"\" package=\"com.emanuelef.remote_capture\" /><node text=\"OK\" package=\"com.emanuelef.remote_capture\" /></hierarchy>"
                else -> ""
            }
        }
        val onboarding = listOf("Next", "Next", "Done")
        val keySteps = listOf("Settings", "Control permissions", "More options", "Generate API key", "OK")
        run.adb.hook("SCREEN_HAS") { extras ->
            val label = extras["label"]
            val count = when {
                label == null -> 1
                page < onboarding.size -> if (label == onboarding[page]) 1 else 0
                else -> if (label == keySteps.getOrNull(page - onboarding.size)) 1 else 0
            }
            "count=$count app=com.emanuelef.remote_capture"
        }
        run.adb.hook("SCREEN_TAP") {
            page++
            "tapped=true count=1"
        }
        run.adb.on("^am start -n com\\.emanuelef\\.remote_capture/\\.activities\\.CaptureCtrl -e action (start|stop) ") {
            running = it.groupValues[1] == "start"
            ""
        }
        run.adb.on("^dumpsys activity services com\\.emanuelef\\.remote_capture$") {
            if (running) "  * ServiceRecord{1a2b u0 com.emanuelef.remote_capture/.CaptureService}" else "  (nothing)"
        }
        val keys = PcapdroidKeys(folder.resolve("pcapdroid-keys.json"))
        val apk = folder.resolve("PCAPdroid_v2.0.4.apk")

        assertEquals("set-up", EgressSetup(run.device, run.hooks, run.clock, keys) { apk }.run())

        assertEquals(KEY, keys.get("emulator-5554"))
        assertTrue(run.adb.hostCommands.contains(listOf("install", "-r", apk.toString())))
        assertTrue(run.adb.commands.containsAll(
            listOf(
                "pm grant com.emanuelef.remote_capture android.permission.POST_NOTIFICATIONS",
                "appops set com.emanuelef.remote_capture ACTIVATE_VPN allow",
                "pm grant com.emanuelef.remote_capture android.permission.ACCESS_LOCAL_NETWORK",
            ),
        ))
        assertEquals(listOf("Next", "Next", "Done", "Settings", "Control permissions", "More options", "Generate API key", "OK"), run.adb.hookCalls("SCREEN_TAP").map { it["label"] })
        assertFalse(running)
        assertTrue(run.console.warnings.none { it.contains(KEY) })
    }

    @Test
    fun aPrivateDnsHostRefusesTheSetupAndAReadyDeviceIsOnlyChecked() {
        val run = FakeRun()
        var running = false
        run.adb.settings["global/private_dns_mode"] = "hostname"
        run.adb.on("^pm list packages com\\.emanuelef\\.remote_capture$") { "package:com.emanuelef.remote_capture" }
        run.adb.on("^am start -n com\\.emanuelef\\.remote_capture/\\.activities\\.CaptureCtrl -e action (start|stop) ") {
            running = it.groupValues[1] == "start"
            ""
        }
        run.adb.on("^dumpsys activity services com\\.emanuelef\\.remote_capture$") { if (running) "  * ServiceRecord{1a2b u0 com.emanuelef.remote_capture/.CaptureService}" else "" }
        val keys = PcapdroidKeys(folder.resolve("ready-keys.json"))
        keys.put("emulator-5554", KEY)
        val setup = EgressSetup(run.device, run.hooks, run.clock, keys) { error("no download") }

        assertThrows(Refusal::class.java) { setup.run() }
        run.adb.settings["global/private_dns_mode"] = "opportunistic"
        assertEquals("checked", setup.run())
        assertTrue(run.adb.commands.none { it.startsWith("monkey") || it.startsWith("pm grant") })
        assertFalse(running)
    }

    private companion object {
        const val KEY = "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
    }
}
