package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.FakeMedia
import dev.kestrel.eval.device.TestPhotos
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.Stopping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Firefox for the tests: its tabs by title, the newest in front. Outside the tabs tray the screen shows the front tab's
 * title and the tab counter, "2 open tabs. Tap to switch tabs." or "1 open tab. …", which opens the tray; the tray shows
 * each tab's title and its close button, "Close tab <title>". The hooks match them as [FakeScreen] says.
 */
class FakeFirefox(adb: FakeAdb) {
    val tabs = mutableListOf("Wikipedia, Common kestrel")
    var tray = false

    /** What a tap on the tab counter does after it opened the tray. */
    var onTrayTap: () -> Unit = {}

    fun labels(): List<String> = if (tray) {
        tabs.flatMap { listOf(it, "Close tab $it") }
    } else {
        listOfNotNull(tabs.lastOrNull(), if (tabs.size == 1) "1 open tab. Tap to switch tabs." else "${tabs.size} open tabs. Tap to switch tabs.")
    }

    fun has(extras: Map<String, String>): Int = FakeScreen.has(labels(), extras)

    fun tap(extras: Map<String, String>): String {
        val labels = labels()
        val matches = FakeScreen.taps(labels, extras)
        val label = labels[matches.firstOrNull() ?: return "tapped=false count=0"]
        when {
            label.startsWith("Close tab ") -> tabs.remove(label.removePrefix("Close tab "))
            label.contains("open tab") -> {
                tray = true
                onTrayTap()
            }
        }
        return "tapped=true count=${matches.size}"
    }

    init {
        adb.on("^monkey -p org\\.mozilla\\.firefox ") {
            tray = false
            ""
        }
        adb.on("^am force-stop org\\.mozilla\\.firefox$") {
            tray = false
            ""
        }
        adb.hook("SCREEN_HAS") { extras -> "count=${has(extras)} app=${D3Tasks.FIREFOX}" }
        adb.hook("SCREEN_TAP") { extras -> tap(extras) }
    }

    companion object {
        /** The title that GitHub gives the repository's page. */
        const val GITHUB = "GitHub - ak800i/kestrel: A private, voice-first assistant"
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
        firefox.tabs.add(FakeFirefox.GITHUB)
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf("Wikipedia, Common kestrel"), firefox.tabs)
        assertEquals(listOf("open tab", "open tabs", "Close tab GitHub - ak800i"), run.adb.hookCalls("SCREEN_TAP").map { it["contains"] })

        firefox.tabs.add(FakeFirefox.GITHUB)
        assertTrue(FirefoxTab().apply(run.scope) is SetupResult.Untestable)
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(2, firefox.tabs.size)
    }

    @Test
    fun ctrlCWhileFirefoxsTabsTrayOpensStopsFirefoxOnlyAfterTheSetupsChangeAndNoChangeFollows() {
        val run = FakeRun()
        val firefox = FakeFirefox(run.adb)
        var hook: Thread? = null
        firefox.onTrayTap = { if (hook == null) hook = run.ctrlC() }

        assertThrows(Stopping::class.java) { FirefoxTab().apply(run.scope) }
        hook!!.join(10_000)

        // Only the restore stops Firefox, after the tray opened; the setup's own stop is a change, which may not start now.
        assertEquals(listOf("am force-stop org.mozilla.firefox"), run.adb.commands.filter { it.startsWith("am force-stop") })
        assertTrue(run.adb.commands.indexOf("am force-stop org.mozilla.firefox") > run.adb.commands.indexOfFirst { it.contains("hook.SCREEN_TAP") })
        assertFalse(firefox.tray)
    }

    @Test
    fun theRehearsalsViewerIsAGalleryThatIsInstalledAndEnabled() {
        val run = FakeRun(DeviceKind.A71)
        // Samsung Gallery is installed but disabled: `pm list packages` names it, `pm list packages -e` does not.
        run.adb.on("^pm list packages (-e )?(\\S+)$") {
            val enabled = it.groupValues[1].isNotEmpty()
            when (it.groupValues[2]) {
                Gallery.SAMSUNG -> if (enabled) "" else "package:${Gallery.SAMSUNG}"
                Gallery.PHOTOS -> "package:${Gallery.PHOTOS}"
                else -> ""
            }
        }
        run.adb.on("^content query --uri ${Regex.escape(TestPhotos.IMAGES)} --projection _id ") { "Row: 0 _id=42" }

        assertEquals(SetupResult.Ready, ViewNewestPhoto.apply(run.scope))
        assertTrue(run.adb.commands.any { it.startsWith("am start -W -a android.intent.action.VIEW -d ${TestPhotos.IMAGES}/42 ") && it.endsWith(" -p ${Gallery.PHOTOS}") })
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals("am force-stop ${Gallery.PHOTOS}", run.adb.commands.last())
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
    fun theCardsTimeCountsOnlyInAViewerAndNeverOnGooglePhotosGrid() {
        val run = FakeRun()
        var activity = "com.google.android.apps.photos/.home.HomeActivity"
        run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 $activity t4}" }
        // The grid's first thumbnail is described as "Photo taken on Nov 23, 2026 1:04:00 PM".
        run.adb.hook("SCREEN_HAS") { extras -> "count=${FakeScreen.has(listOf("Photo taken on Nov 23, 2026 1:04:00 PM"), extras)} app=${Gallery.PHOTOS}" }
        run.adb.hook("AGENT_TREE") { "tree=written path=cache/agent-tree.json windows=1 elements=2" }
        run.adb.files["cache/agent-tree.json"] = TREE.replace("com.sec.android.gallery3d", Gallery.PHOTOS).replace("[0,200,1080,2000]", "[0,200,300,500]")
        run.scope.notes[Gallery.TIMES] = "1:04,13:04"

        assertFalse(PhotoViewer.passes(run.scope, none))
        activity = "com.google.android.apps.photos/.pager.HostPhotoPagerActivity"
        assertTrue(PhotoViewer.passes(run.scope, none))
    }

    @Test
    fun aNewestCardThatAndroidDatesAtAnotherTimeFailsTheSetupAndLeaves() {
        val run = FakeRun()
        val media = FakeMedia(run.adb)
        run.adb.on("^date \\+%Y-%m-%dT%H:%M:%S$") { "2026-11-23T13:05:30" }
        // Android took the copy's time, 13:05:30, instead of the card's 13:04.
        media.newestTaken = 1795435530000L

        assertThrows(SetupFailed::class.java) { NewestPhoto.apply(run.scope) }
        assertEquals(0, media.test.size)
        assertTrue(run.restores.runAll().isEmpty())
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
