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
import java.nio.file.Path

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
        // PCAPdroid 2.0.4's pages: a preference row is named after its title and summary, so only its title's text has
        // the label, and a menu item counts twice, its row and its text.
        val app = FakePcapdroid(
            listOf(Shown("Next")),
            listOf(Shown("Next")),
            listOf(Shown("DONE")),
            listOf(Shown("Settings")),
            listOf(Shown("Control permissions Allow apps to control PCAPdroid", swipes = 6), Shown("Control permissions", clickable = false, swipes = 6)),
            listOf(Shown("More options")),
            listOf(Shown("Generate API key"), Shown("Generate API key", clickable = false)),
            listOf(Shown("OK")),
        )
        val keys = PcapdroidKeys(folder.resolve("pcapdroid-keys.json"))
        val apk = folder.resolve("PCAPdroid_v2.0.4.apk")

        assertEquals("set-up", app.setup(keys, apk).run())

        assertEquals(KEY, keys.get("emulator-5554"))
        assertTrue(app.run.adb.hostCommands.contains(listOf("install", "-r", apk.toString())))
        val commands = app.run.adb.commands
        assertTrue(commands.containsAll(
            listOf(
                "pm grant com.emanuelef.remote_capture android.permission.POST_NOTIFICATIONS",
                "appops set com.emanuelef.remote_capture ACTIVATE_VPN allow",
                "pm grant com.emanuelef.remote_capture android.permission.ACCESS_LOCAL_NETWORK",
            ),
        ))
        val stopped = commands.indexOf("am force-stop com.emanuelef.remote_capture")
        assertTrue(stopped >= 0 && stopped < commands.indexOfFirst { it.startsWith("monkey -p com.emanuelef.remote_capture ") })
        assertEquals(listOf("Next", "Next", "DONE", "Settings", "Control permissions", "More options", "Generate API key", "OK"), app.taps())
        assertFalse(app.running)
        assertEquals("input keyevent KEYCODE_HOME", commands.last())
        assertTrue(app.run.console.warnings.none { it.contains(KEY) })
    }

    @Test
    fun aPcapdroidThatHasAKeyShowsItAndNeverDiscardsIt() {
        val app = FakePcapdroid(
            listOf(Shown("Settings")),
            listOf(Shown("Control permissions")),
            listOf(Shown("More options")),
            listOf(Shown("Generate API key"), Shown("Show API key")),
            listOf(Shown("OK")),
        )
        val keys = PcapdroidKeys(folder.resolve("lost-keys.json"))

        assertEquals("set-up", app.setup(keys).run())

        assertEquals(KEY, keys.get("emulator-5554"))
        assertEquals(listOf("Settings", "Control permissions", "More options", "Show API key", "OK"), app.taps())
    }

    @Test
    fun theFirstLabelThatTapsWinsAndAStepThatCannotBePressedStopsWhereItIs() {
        val done = FakePcapdroid(listOf(Shown("Done")), listOf(Shown("Settings")), refused = setOf("Settings"))
        val stuck = assertThrows(IllegalStateException::class.java) { done.setup().run() }
        assertTrue(stuck.message, stuck.message!!.contains("could not press Settings"))
        assertEquals(listOf("DONE", "Done", "Settings"), done.taps())

        val covered = FakePcapdroid(listOf(Shown("OK")), refused = setOf("OK"))
        val first = assertThrows(IllegalStateException::class.java) { covered.setup().run() }
        assertTrue(first.message, first.message!!.contains("cannot press"))
        assertEquals(listOf("OK"), covered.taps())
    }

    @Test
    fun theFirstStartTapsOnlyInPcapdroidAndOnceInOneUisNotice() {
        // Another app's dialog in front: its "OK" is never tapped, and the first start stops there.
        val other = FakePcapdroid(listOf(Shown("OK", app = "com.example.other"), Shown("Next", app = "com.example.other")), listOf(Shown("DONE")))
        val stopped = assertThrows(IllegalStateException::class.java) { other.setup().run() }
        assertTrue(stopped.message, stopped.message!!.contains("stopped at com.example.other"))
        assertEquals(emptyList<String?>(), other.taps())

        // One UI's one-time notice over the launcher is answered once, in SystemUI only.
        val notice = FakePcapdroid(
            listOf(Shown("OK", app = SYSTEM_UI)),
            listOf(Shown("DONE")),
            listOf(Shown("Settings")),
            listOf(Shown("Control permissions")),
            listOf(Shown("More options")),
            listOf(Shown("Show API key")),
            listOf(Shown("OK")),
        )
        assertEquals("set-up", notice.setup().run())
        assertEquals(listOf("OK", "DONE", "Settings", "Control permissions", "More options", "Show API key", "OK"), notice.taps())
        assertEquals(listOf(SYSTEM_UI) + List(6) { Pcapdroid.PACKAGE }, notice.tapsIn())

        // A second notice is not answered.
        val twice = FakePcapdroid(listOf(Shown("OK", app = SYSTEM_UI)), listOf(Shown("OK", app = SYSTEM_UI)), listOf(Shown("DONE")))
        assertThrows(IllegalStateException::class.java) { twice.setup().run() }
        assertEquals(listOf("OK"), twice.taps())
    }

    @Test
    fun aLabelThatShowsTwiceIsNeverTapped() {
        val app = FakePcapdroid(listOf(Shown("DONE")), listOf(Shown("Settings"), Shown("Settings")))

        val twice = assertThrows(IllegalStateException::class.java) { app.setup().run() }

        assertTrue(twice.message, twice.message!!.contains("Settings"))
        assertEquals(listOf("DONE"), app.taps())
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
        assertEquals("input keyevent KEYCODE_HOME", run.adb.commands.last())
    }

    private class Shown(val label: String, val clickable: Boolean = true, val swipes: Int = 0, val app: String = Pcapdroid.PACKAGE)

    /**
     * PCAPdroid behind the hooks, page by page, on a device without it: screen-has compares labels without letter case, as
     * ScreenQuery does, and counts only the elements of its `package`; screen-tap presses only an exact label, as
     * tapMatches does, and with a `package` only while that app's page is in front; a tap on a label in [refused] is
     * answered as one in another window. A page belongs to the app of its first element, PCAPdroid unless it names
     * another. A pressed label opens the next page, and an element shows after its swipes.
     */
    private inner class FakePcapdroid(private vararg val pages: List<Shown>, refused: Set<String> = emptySet()) {
        val run = FakeRun()
        var running = false
        private var installed = false
        private var page = 0
        private var swipes = 0

        init {
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
            run.adb.hook("SCREEN_HAS") { extras ->
                val label = extras["label"]
                val shown = showing().filter {
                    (extras["package"] ?: it.app) == it.app && it.label.equals(label, ignoreCase = true) && (extras["state"] != "clickable" || it.clickable)
                }
                "count=${if (label == null) 1 else shown.size} app=${front()}"
            }
            run.adb.hook("SCREEN_TAP") { extras ->
                val label = extras["label"]
                val matches = showing().count { it.label == label }
                when {
                    extras["package"].let { it != null && it != front() } -> "tapped=false error=elsewhere"
                    label in refused -> "tapped=false error=elsewhere"
                    matches == 0 -> "tapped=false count=0"
                    else -> "tapped=true count=$matches".also { page++; swipes = 0 }
                }
            }
            run.adb.on("^am start -n com\\.emanuelef\\.remote_capture/\\.activities\\.CaptureCtrl -e action (start|stop) ") {
                running = it.groupValues[1] == "start"
                ""
            }
            run.adb.on("^dumpsys activity services com\\.emanuelef\\.remote_capture$") {
                if (running) "  * ServiceRecord{1a2b u0 com.emanuelef.remote_capture/.CaptureService}" else "  (nothing)"
            }
            run.adb.on("^input swipe ") { swipes++; "" }
        }

        private fun showing(): List<Shown> = pages.getOrNull(page).orEmpty().filter { it.swipes <= swipes }

        private fun front(): String = pages.getOrNull(page)?.firstOrNull()?.app ?: Pcapdroid.PACKAGE

        fun setup(keys: PcapdroidKeys = PcapdroidKeys(folder.resolve("keys-${System.nanoTime()}.json")), apk: Path = folder.resolve("PCAPdroid_v2.0.4.apk")) =
            EgressSetup(run.device, run.hooks, run.clock, keys) { apk }

        fun taps(): List<String?> = run.adb.hookCalls("SCREEN_TAP").map { it["label"] }

        fun tapsIn(): List<String?> = run.adb.hookCalls("SCREEN_TAP").map { it["package"] }
    }

    private companion object {
        const val KEY = "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
        const val SYSTEM_UI = "com.android.systemui"
    }
}
