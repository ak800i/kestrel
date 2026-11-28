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
 * Firefox for the tests, as on kestrel-api37: its tabs by title and address, the newest selected and in front. Outside
 * the tabs tray the screen shows the selected tab's address bar ("<address>. Search or enter address") and the tab
 * counter, a clickable element with an inner copy of its label, "Non-private Tabs Open: 2. Tap to switch tabs.", which
 * opens the tray, and whose long press opens a menu with "Close tab", which closes the selected tab and shows "Tab
 * closed". The tray shows each tab's card, whose label holds its title and "Close tab <title>", its title, and its close
 * button, "Close tab <title>", and then the tray's own counters; closing its last tab closes the tray, and Firefox shows
 * its homepage with the counter and the [topSites]. The hooks match them as [FakeScreen] says.
 */
class FakeFirefox(adb: FakeAdb) {
    class Tab(val title: String, val address: String)

    private class Element(val label: String, val className: String, val clickable: Boolean, val bounds: String, val tab: Tab? = null)

    val tabs = mutableListOf(Tab(WIKI, WIKI_ADDRESS))
    var tray = false
    var menu = false
    var closed = false

    /** The titles of the homepage's top sites, which it shows while Firefox has no tab, as the A71's shows pages of earlier runs. */
    var topSites: List<String> = emptyList()

    /** What a tap on the tab counter does after it opened the tray. */
    var onTrayTap: () -> Unit = {}

    fun titles(): List<String> = tabs.map { it.title }

    fun open(title: String, address: String) {
        tabs.add(Tab(title, address))
    }

    /** The number in the tab counter's label for a number of tabs; the A71's fresh Firefox shows no number for none. */
    var number: (Int) -> String = { "$it" }

    private fun counter(): String = "Non-private Tabs Open: ${number(tabs.size)}. Tap to switch tabs."

    private fun elements(): List<Element> = when {
        tray -> tabs.withIndex().flatMap { (index, tab) ->
            val top = 600 + 600 * index
            listOf(
                Element("${tab.title} Close tab ${tab.title}", "android.view.View", true, "42,$top,519,${top + 590}", tab),
                Element(tab.title, "android.widget.TextView", false, "106,${top + 30},403,${top + 70}", tab),
                Element("Close tab ${tab.title}", "android.widget.Button", true, "405,$top,531,${top + 120}", tab),
            )
        } + listOf(
            Element("Private Tabs Open: 0. Tap to switch tabs.", "android.view.View", true, "0,132,270,258"),
            Element("Normal Tabs Open: ${tabs.size}. Tap to switch tabs.", "android.view.View", false, "270,132,540,258"),
            Element(counter(), "android.view.View", false, "374,164,437,227"),
        )
        else -> listOfNotNull(
            tabs.lastOrNull()?.let { Element("${it.address}. Search or enter address", "android.view.View", false, "150,153,800,279") },
            Element(counter(), "android.view.View", true, "828,153,954,279"),
            Element(counter(), "android.view.View", false, "860,185,923,248"),
        ) + (if (tabs.isEmpty()) topSites.map { Element(it, "android.widget.TextView", false, "68,801,289,895") } else emptyList()) + (if (menu) listOf("New tab", "New private tab", "Close tab").map { Element(it, "android.widget.Button", true, "0,1800,1080,1900") } else emptyList()) +
            (if (closed) listOf(Element("Tab closed", "android.widget.TextView", false, "63,2000,843,2100"), Element("UNDO", "android.view.View", true, "864,2000,1038,2100")) else emptyList())
    }

    /** screen-has' answer: the count, the first element's bounds and the app. */
    fun answer(extras: Map<String, String>): String {
        val found = elements().filter { element ->
            FakeScreen.has(listOf(element.label), extras) == 1 &&
                (extras["class"]?.let { element.className.endsWith(".$it") } ?: true) &&
                (extras["state"] != "clickable" || element.clickable)
        }
        return "count=${found.size} bounds=${found.firstOrNull()?.bounds ?: "none"} app=${D3Tasks.FIREFOX}"
    }

    fun tap(extras: Map<String, String>): String {
        val all = elements()
        val matches = FakeScreen.taps(all.map { it.label }, extras)
        val element = all[matches.firstOrNull() ?: return "tapped=false count=0"]
        val label = element.label
        when {
            label == "Close tab" && menu -> {
                tabs.removeLastOrNull()
                menu = false
                closed = true
            }
            label.startsWith("Close tab ") && element.className.endsWith("Button") -> {
                tabs.remove(element.tab)
                if (tabs.isEmpty()) tray = false
            }
            label.startsWith("Non-private Tabs Open") && !tray && extras["long"] == "true" -> menu = true
            label.startsWith("Non-private Tabs Open") && !tray -> {
                tray = true
                onTrayTap()
            }
        }
        return "tapped=true count=${matches.size}"
    }

    init {
        adb.on("^monkey -p org\\.mozilla\\.firefox ") {
            tray = false
            menu = false
            ""
        }
        adb.on("^am force-stop org\\.mozilla\\.firefox$") {
            tray = false
            menu = false
            closed = false
            ""
        }
        adb.hook("SCREEN_HAS") { extras -> answer(extras) }
        adb.hook("SCREEN_TAP") { extras -> tap(extras) }
    }

    companion object {
        const val WIKI = "Common kestrel - Wikipedia"
        const val WIKI_ADDRESS = "en.wikipedia.org/wiki/Common_kestrel"

        /** The title that GitHub gives the repository's page, and the page's address. */
        const val GITHUB = "GitHub - ak800i/kestrel: A private, voice-first assistant for Android that performs tasks through the accessibility " +
            "service. Only the configured LLM provider receives data. · GitHub"
        const val GITHUB_ADDRESS = "github.com/ak800i/kestrel"
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
        firefox.open(FakeFirefox.GITHUB, FakeFirefox.GITHUB_ADDRESS)
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(FakeFirefox.WIKI), firefox.titles())
        // The counter by its one clickable element, though its inner copy holds the same label; the close button by its exact label, which the card holds too.
        val counter = mapOf("contains" to "Non-private Tabs Open", "package" to D3Tasks.FIREFOX)
        assertEquals(listOf(counter, counter, mapOf("label" to "Close tab ${FakeFirefox.GITHUB}", "package" to D3Tasks.FIREFOX)), run.adb.hookCalls("SCREEN_TAP"))

        firefox.open(FakeFirefox.GITHUB, FakeFirefox.GITHUB_ADDRESS)
        assertTrue(FirefoxTab().apply(run.scope) is SetupResult.Untestable)
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(2, firefox.tabs.size)
    }

    @Test
    fun firefoxsTabOfThePageIsClosedOnTheA71WhoseHomepageShowsThePageOnceItsLastTabClosedAndNoOtherTabIsClosed() {
        val run = FakeRun(DeviceKind.A71)
        val firefox = FakeFirefox(run.adb)
        // The A71's Firefox has no tab before the run, and its homepage's top sites hold the page, from earlier runs.
        firefox.tabs.clear()
        firefox.topSites = listOf(FakeFirefox.GITHUB)

        assertEquals(SetupResult.Ready, FirefoxTab().apply(run.scope))
        firefox.open(FakeFirefox.GITHUB, FakeFirefox.GITHUB_ADDRESS)
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(firefox.tabs.isEmpty())

        // A run that opened no tab: nothing is closed, and nothing is left to put back.
        assertEquals(SetupResult.Ready, FirefoxTab().apply(run.scope))
        val taps = run.adb.hookCalls("SCREEN_TAP").size
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(taps + 1, run.adb.hookCalls("SCREEN_TAP").size)

        // Two tabs of the page, of which the run would have opened at most one: none is closed, and the restore says so.
        assertEquals(SetupResult.Ready, FirefoxTab().apply(run.scope))
        repeat(2) { firefox.open(FakeFirefox.GITHUB, FakeFirefox.GITHUB_ADDRESS) }
        assertEquals(listOf("the run's tab in Firefox"), run.restores.runAll().map { it.what })
        assertEquals(2, firefox.tabs.size)
    }

    @Test
    fun firefoxsFirstRunQuestionAboutTheDefaultBrowserMakesItsTasksUntestableAndIsClosedUnanswered() {
        val run = FakeRun(DeviceKind.A71)
        FakeFirefox(run.adb)
        var asking = false
        // The A71's Firefox, which has not been through its first run, asks Android to make it the default browser when it starts.
        run.adb.on("^monkey -p org\\.mozilla\\.firefox ") {
            asking = true
            ""
        }
        run.adb.on("^dumpsys activity activities$") {
            val front = if (asking) "com.google.android.permissioncontroller/com.android.permissioncontroller.role.ui.RequestRoleActivity" else "org.mozilla.firefox/.App"
            "  topResumedActivity=ActivityRecord{a1 u0 $front t932}"
        }
        run.adb.on("^am stack list$") {
            val top = if (asking) "com.google.android.permissioncontroller/com.android.permissioncontroller.role.ui.RequestRoleActivity" else "org.mozilla.firefox/org.mozilla.firefox.App"
            listOf(
                "RootTask id=932 bounds=[0,0][1080,2400] displayId=0 userId=0",
                "  taskId=932: org.mozilla.firefox/org.mozilla.firefox.App bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{$top}",
                "RootTask id=1 bounds=[0,0][1080,2400] displayId=0 userId=0",
                "  taskId=940: com.sec.android.app.launcher/.activities.LauncherActivity bounds=[0,0][1080,2400] userId=0 visible=true topActivity=ComponentInfo{com.sec.android.app.launcher/.activities.LauncherActivity}",
            ).joinToString("\n")
        }
        run.adb.on("^am stack remove (\\d+)$") {
            if (it.groupValues[1] == "932") asking = false
            ""
        }
        run.adb.hook("SCREEN_HAS") { "count=0 bounds=none app=com.google.android.permissioncontroller" }

        for (setup in listOf(FirefoxTab(), FirefoxNewTab("https://en.wikipedia.org/wiki/Common_kestrel", FakeFirefox.WIKI_ADDRESS))) {
            assertEquals(SetupResult.Untestable(Firefox.FIRST_RUN_WHY), setup.apply(run.scope))
            assertTrue(run.restores.runAll().isEmpty())
            assertFalse(asking)
        }
        assertEquals(listOf("am stack remove 932", "am stack remove 932"), run.adb.commands.filter { it.startsWith("am stack remove") })
        assertTrue(run.adb.commands.none { it.startsWith("input keyevent") })
        assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
    }

    @Test
    fun firefoxWikisRehearsalClosesOnlyItsOwnSelectedTabThroughTheCountersMenu() {
        val run = FakeRun()
        val firefox = FakeFirefox(run.adb)
        // The owner's tab of the same page stays.
        firefox.open(FakeFirefox.GITHUB, FakeFirefox.GITHUB_ADDRESS)
        var opens = true
        run.adb.on("^am start -W -a android.intent.action.VIEW -d 'https://en\\.wikipedia\\.org/wiki/Common_kestrel' -p org\\.mozilla\\.firefox$") {
            if (opens) firefox.open(FakeFirefox.WIKI, FakeFirefox.WIKI_ADDRESS)
            ""
        }
        run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 org.mozilla.firefox/.App t4}" }
        val steps = (D3Tasks.FIREFOX_WIKI.rehearsal as Rehearsal.Steps).steps

        for (step in steps) assertEquals(SetupResult.Ready, step.apply(run.scope))
        assertEquals(3, firefox.tabs.size)
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(FakeFirefox.WIKI, FakeFirefox.GITHUB), firefox.titles())
        assertEquals(
            listOf(mapOf("contains" to "Non-private Tabs Open", "long" to "true", "package" to D3Tasks.FIREFOX), mapOf("label" to "Close tab", "package" to D3Tasks.FIREFOX)),
            run.adb.hookCalls("SCREEN_TAP"),
        )

        // A page that did not open added no tab, so no tab is closed, and nothing is left to put back.
        opens = false
        for (step in steps) assertEquals(SetupResult.Ready, step.apply(run.scope))
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(FakeFirefox.WIKI, FakeFirefox.GITHUB), firefox.titles())
        assertEquals(2, run.adb.hookCalls("SCREEN_TAP").size)

        // An earlier run's tab of the page is selected, as on kestrel-api37, where Firefox then selects it and opens no
        // new tab: that tab stays.
        firefox.open(FakeFirefox.WIKI, FakeFirefox.WIKI_ADDRESS)
        for (step in steps) assertEquals(SetupResult.Ready, step.apply(run.scope))
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(FakeFirefox.WIKI, FakeFirefox.GITHUB, FakeFirefox.WIKI), firefox.titles())
        assertEquals(2, run.adb.hookCalls("SCREEN_TAP").size)
        // A page that opened added a tab, and only that one is closed.
        opens = true
        for (step in steps) assertEquals(SetupResult.Ready, step.apply(run.scope))
        assertEquals(4, firefox.tabs.size)
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(FakeFirefox.WIKI, FakeFirefox.GITHUB, FakeFirefox.WIKI), firefox.titles())

        // One tab more, but the selected tab is not the page's, or two tabs more: no tab is closed, and the restore says so.
        for (extra in listOf(listOf(FakeFirefox.GITHUB to FakeFirefox.GITHUB_ADDRESS), listOf(FakeFirefox.WIKI to FakeFirefox.WIKI_ADDRESS, FakeFirefox.WIKI to FakeFirefox.WIKI_ADDRESS))) {
            opens = false
            for (step in steps) assertEquals(SetupResult.Ready, step.apply(run.scope))
            val before = firefox.titles()
            for ((title, address) in extra) firefox.open(title, address)
            val taps = run.adb.hookCalls("SCREEN_TAP").size
            assertEquals(listOf("the rehearsal's tab in Firefox"), run.restores.runAll().map { it.what })
            assertEquals(before + extra.map { it.first }, firefox.titles())
            assertEquals(taps, run.adb.hookCalls("SCREEN_TAP").size)
            repeat(extra.size) { firefox.tabs.removeAt(firefox.tabs.lastIndex) }
            run.console.warnings.clear()
        }
    }

    @Test
    fun firefoxWikisRehearsalReadsAnEmptyTabCounterAsNoTabsAndClosesOnlyTheTabThatItsOpenMade() {
        for (empty in listOf("0", "")) {
            val run = FakeRun(DeviceKind.A71)
            val firefox = FakeFirefox(run.adb)
            // A fresh Firefox whose tabs the owner closed: its tab counter shows 0, or no number at all.
            firefox.tabs.clear()
            firefox.number = { if (it == 0) empty else "$it" }
            var opens = true
            run.adb.on("^am start -W -a android.intent.action.VIEW -d 'https://en\\.wikipedia\\.org/wiki/Common_kestrel' -p org\\.mozilla\\.firefox$") {
                if (opens) firefox.open(FakeFirefox.WIKI, FakeFirefox.WIKI_ADDRESS)
                ""
            }
            run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 org.mozilla.firefox/.App t4}" }
            val steps = (D3Tasks.FIREFOX_WIKI.rehearsal as Rehearsal.Steps).steps

            for (step in steps) assertEquals(empty, SetupResult.Ready, step.apply(run.scope))
            assertEquals(empty, 1, firefox.tabs.size)
            assertTrue(empty, run.restores.runAll().isEmpty())
            assertTrue(empty, firefox.tabs.isEmpty())
            assertEquals(
                empty,
                listOf(mapOf("contains" to "Non-private Tabs Open", "long" to "true", "package" to D3Tasks.FIREFOX), mapOf("label" to "Close tab", "package" to D3Tasks.FIREFOX)),
                run.adb.hookCalls("SCREEN_TAP"),
            )

            // A page that did not open added no tab: nothing is closed, and nothing is left to put back.
            opens = false
            for (step in steps) assertEquals(empty, SetupResult.Ready, step.apply(run.scope))
            assertTrue(empty, run.restores.runAll().isEmpty())
            assertEquals(empty, 2, run.adb.hookCalls("SCREEN_TAP").size)

            // Two tabs more: no tab is closed, and the restore says so.
            for (step in steps) assertEquals(empty, SetupResult.Ready, step.apply(run.scope))
            repeat(2) { firefox.open(FakeFirefox.WIKI, FakeFirefox.WIKI_ADDRESS) }
            assertEquals(empty, listOf("the rehearsal's tab in Firefox"), run.restores.runAll().map { it.what })
            assertEquals(empty, 2, firefox.tabs.size)
            assertEquals(empty, 2, run.adb.hookCalls("SCREEN_TAP").size)
        }
    }

    @Test
    fun firefoxWikisRehearsalIsUntestableWhenNoTabCounterShows() {
        val run = FakeRun()
        FakeFirefox(run.adb)
        run.adb.hook("SCREEN_HAS") { "count=0 app=${D3Tasks.FIREFOX}" }

        assertTrue(FirefoxNewTab("https://en.wikipedia.org/wiki/Common_kestrel", FakeFirefox.WIKI_ADDRESS).apply(run.scope) is SetupResult.Untestable)
        assertTrue(run.adb.commands.none { it.startsWith("am start") })
        assertTrue(run.restores.runAll().isEmpty())
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
        assertTrue(run.adb.commands.indexOf("am force-stop org.mozilla.firefox") > run.adb.commands.indexOfFirst { it.contains("--method SCREEN_TAP ") })
        assertFalse(firefox.tray)
    }

    @Test
    fun youtubesNotificationQuestionIsDismissedWithoutAnAnswerAndYouTubeStaysOpen() {
        // What Back does to the dialog at each try: close it and leave YouTube open, close YouTube too, or nothing.
        for (backs in listOf(listOf("open"), listOf("closed", "open"), listOf("stays", "stays"))) {
            val run = FakeRun()
            val youtube = "com.google.android.youtube"
            run.adb.on("^pm list packages -e ") { "package:$youtube" }
            var front = "com.google.android.apps.nexuslauncher"
            run.adb.on("^am start -W -a android.intent.action.VIEW -d 'https://www\\.youtube\\.com/' -p com\\.google\\.android\\.youtube$") {
                front = "com.google.android.permissioncontroller"
                ""
            }
            var back = 0
            run.adb.on("^input keyevent KEYCODE_BACK$") {
                when (backs[back++]) {
                    "open" -> front = youtube
                    "closed" -> front = "com.google.android.apps.nexuslauncher"
                }
                ""
            }
            run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 $front/.Main t4}" }
            run.adb.on("^dumpsys package com\\.google\\.android\\.youtube$") {
                "      android.permission.POST_NOTIFICATIONS: granted=false, flags=[ USER_SENSITIVE_WHEN_GRANTED|USER_SENSITIVE_WHEN_DENIED]"
            }

            val result = DismissPermissionPrompt(AppTasks.YOUTUBE, DismissPermissionPrompt.NOTIFICATIONS, "https://www.youtube.com/").apply(run.scope)

            val expected = if (backs.last() == "open") SetupResult.Ready else SetupResult.Untestable("Android asks whether $youtube may have android.permission.POST_NOTIFICATIONS, which only the owner may answer")
            assertEquals(backs.toString(), expected, result)
            // Never a tap: no answer, and so no grant.
            assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
            assertEquals(backs.size, back)
            assertEquals("input keyevent KEYCODE_HOME", run.adb.commands.last { it.startsWith("input") || it.startsWith("am force-stop") })
            assertTrue(run.restores.runAll().isEmpty())
            assertEquals("am force-stop $youtube", run.adb.commands.last())
        }
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
