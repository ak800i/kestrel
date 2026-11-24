package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.FakeMedia
import dev.kestrel.eval.device.TestPhotos
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Firefox for the tests: its tabs by title, its tabs tray, which the tab counter opens, and each tab's close button,
 * "Close tab <title>", which the hooks count and tap.
 */
class FakeFirefox(adb: FakeAdb) {
    val tabs = mutableListOf("Wikipedia, Common kestrel")
    var tray = false

    init {
        adb.on("^monkey -p org\\.mozilla\\.firefox ") {
            tray = false
            ""
        }
        adb.on("^am force-stop org\\.mozilla\\.firefox$") {
            tray = false
            ""
        }
        adb.hook("SCREEN_HAS") { extras ->
            val contains = extras["contains"].orEmpty()
            val count = when {
                contains == "open tabs" -> if (tray) 0 else 1
                contains.startsWith("Close tab ") -> if (tray) tabs.count { it.contains(contains.removePrefix("Close tab ").substringAfterLast(' ')) } else 0
                contains.isNotEmpty() -> tabs.count { it.contains(contains) }
                else -> 0
            }
            "count=$count app=${D3Tasks.FIREFOX}"
        }
        adb.hook("SCREEN_TAP") { extras ->
            val contains = extras["contains"].orEmpty()
            if (contains == "open tabs") tray = true
            if (contains.startsWith("Close tab ")) tabs.remove(tabs.first { it.contains("ak800i") })
            "tapped=true count=1"
        }
    }
}

/** The steps and checks of the app tasks (design D8 and D9 of sub-project 6). */
class AppStepsTest {
    private val none = HookAnswer.EMPTY

    @Test
    fun anAppThatIsNotInstalledMakesTheTaskUntestable() {
        val run = FakeRun()
        run.adb.on("^pm list packages -e com\\.google\\.android\\.youtube$") { "package:com.google.android.youtube" }

        assertEquals(SetupResult.Ready, Installed(listOf("com.google.android.youtube")).apply(run.scope))
        assertEquals(SetupResult.Ready, Installed(listOf("com.example.none", "com.google.android.youtube")).apply(run.scope))
        assertTrue(Installed(listOf("com.example.none")).apply(run.scope) is SetupResult.Untestable)
    }

    @Test
    fun firefoxsTabOfThePageIsClosedInTheTabsTrayAfterTheRunAndAnOlderOneRefusesTheRun() {
        val run = FakeRun()
        val firefox = FakeFirefox(run.adb)

        assertEquals(SetupResult.Ready, FirefoxTab().apply(run.scope))
        assertEquals("am force-stop org.mozilla.firefox", run.adb.commands.last())
        firefox.tabs.add("GitHub - ak800i/kestrel")
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf("Wikipedia, Common kestrel"), firefox.tabs)
        assertEquals(listOf("open tabs", "open tabs", "Close tab GitHub ak800i"), run.adb.hookCalls("SCREEN_TAP").map { it["contains"] })

        firefox.tabs.add("GitHub - ak800i/kestrel")
        assertTrue(FirefoxTab().apply(run.scope) is SetupResult.Untestable)
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(2, firefox.tabs.size)
    }

    @Test
    fun theNewestPhotoIsACardTakenAMinuteAgoThatLeavesWithItsFolder() {
        val run = FakeRun()
        val media = FakeMedia(run.adb)
        run.adb.on("^date \\+%Y-%m-%dT%H:%M:%S$") { "2026-11-23T13:05:30" }
        // The owner's photos of today do not stop this card, which is never shared.
        media.owner.add("Row: 0 _id=31, datetaken=1795392000000, date_added=1795392000")

        assertEquals(LocalDateTime.of(2026, 11, 23, 13, 4), TestPhotos(run.device, run.scope.work.resolve("photos")).addNewest())
        assertTrue(run.adb.commands.contains("touch -m -d '2026-11-23 13:04:00' ${TestPhotos.FOLDER}/kestrel_test_newest.jpg"))
        assertEquals(1, media.test.size)
        media.test.clear()

        assertEquals(SetupResult.Ready, NewestPhoto.apply(run.scope))
        assertEquals("1:04,13:04", run.scope.notes[Gallery.TIMES])
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(0, media.test.size)
    }

    @Test
    fun theViewerShowsTheCardsTimeOrElseOneImageThatCoversItsWindow() {
        val run = FakeRun(DeviceKind.A71)
        var front = Gallery.SAMSUNG
        run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 $front/.app.GalleryActivity t4}" }
        var times = 1
        run.adb.hook("SCREEN_HAS") { extras -> "count=${if (extras["contains"] == "13:04") times else 0} app=$front" }
        run.adb.hook("AGENT_TREE") { "tree=written path=cache/agent-tree.json windows=1 elements=2" }
        run.adb.files["cache/agent-tree.json"] = TREE
        run.scope.notes[Gallery.TIMES] = "1:04,13:04"

        assertTrue(PhotoViewer.passes(run.scope, none))
        times = 0
        assertTrue(PhotoViewer.passes(run.scope, none))
        run.adb.files["cache/agent-tree.json"] = TREE.replace("[0,200,1080,2000]", "[0,200,300,500]")
        assertFalse(PhotoViewer.passes(run.scope, none))
        times = 1
        front = "com.sec.android.app.launcher"
        assertFalse(PhotoViewer.passes(run.scope, none))
    }

    @Test
    fun aLargeImageCoversMostOfTheActiveApplicationWindow() {
        assertEquals(1, Dumps.largeImageCount(TREE))
        assertEquals(0, Dumps.largeImageCount(TREE.replace("[0,200,1080,2000]", "[0,200,300,500]")))
        assertEquals(0, Dumps.largeImageCount("""{"windows":[],"elements":[]}"""))
    }

    private companion object {
        val TREE = """
            {"windows":[{"id":4,"kind":"APPLICATION","packageName":"com.sec.android.gallery3d","active":true,"bounds":[0,0,1080,2400]}],
             "elements":[{"windowId":4,"className":"android.widget.ImageView","bounds":[0,200,1080,2000]},
                         {"windowId":4,"className":"android.widget.ImageButton","bounds":[0,2200,200,2400]}]}
        """.trimIndent()
    }
}
