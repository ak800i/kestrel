package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeMedia
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app tasks against a scripted device that passes and one that fails, with every restore run (design D8 and D9 of sub-project 6). */
class AppTasksTest {
    @Test
    fun firefoxUrlPassesWithThePageInFrontAndClosesItsTab() {
        val bench = TaskBench()
        val firefox = FakeFirefox(bench.adb)
        bench.adb.on("^pm list packages -e org\\.mozilla\\.firefox$") { "package:org.mozilla.firefox" }
        bench.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 org.mozilla.firefox/.App t4}" }
        var works = true
        bench.adb.hook("VOICE") { extras ->
            if (works && extras["text"] == "Kestrel, open github.com/ak800i/kestrel in Firefox") firefox.tabs.add(FakeFirefox.GITHUB)
            "sent"
        }

        assertEquals(Reason.OK, bench.run(AppTasks.FIREFOX_URL).reason)
        assertEquals(listOf("Wikipedia, Common kestrel"), firefox.tabs)
        works = false
        assertEquals(Reason.CHECK, bench.run(AppTasks.FIREFOX_URL).reason)
        bench.assertPutBack()
    }

    @Test
    fun youtubeSearchAndMapsPlacePassWithTheirAppInFrontShowingTheWords() {
        for ((task, app) in listOf(AppTasks.YOUTUBE_SEARCH to "com.google.android.youtube", AppTasks.MAPS_PLACE to AppTasks.MAPS)) {
            val bench = TaskBench()
            bench.adb.on("^pm list packages ") { "package:$app" }
            var front = "com.google.android.apps.nexuslauncher"
            bench.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 $front/.Main t4}" }
            bench.adb.hook("SCREEN_HAS") { extras -> "count=${if (front == app && extras["contains"] in setOf("kestrel hovering", "Kalemegdan")) 1 else 0} app=$front" }
            var works = true
            bench.adb.hook("VOICE") {
                if (works) front = app
                "sent"
            }

            assertEquals(task.name, Reason.OK, bench.run(task).reason)
            works = false
            front = "com.google.android.apps.nexuslauncher"
            assertEquals(task.name, Reason.CHECK, bench.run(task).reason)
            bench.assertPutBack()
            assertTrue(task.name, bench.adb.commands.contains("am force-stop $app"))
        }
    }

    @Test
    fun youtubeSearchOnTheA71TakesReVancedsYouTube() {
        val bench = TaskBench(DeviceKind.A71)
        val revanced = "app.revanced.android.youtube"
        bench.adb.on("^pm list packages -e ") { "package:$revanced" }
        var front = "com.sec.android.app.launcher"
        bench.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 $front/.Main t4}" }
        bench.adb.hook("SCREEN_HAS") { extras -> "count=${if (front == revanced && extras["contains"] == "kestrel hovering") 1 else 0} app=$front" }
        bench.adb.hook("VOICE") {
            front = revanced
            "sent"
        }
        bench.adb.on("^am start -W -a android.intent.action.VIEW -d '(\\S+)' -p (\\S+)$") {
            front = it.groupValues[2]
            ""
        }

        assertEquals(Reason.OK, bench.run(AppTasks.YOUTUBE_SEARCH).reason)
        bench.assertPutBack()
        front = "com.sec.android.app.launcher"
        val scope = bench.run.scope
        for (step in AppTasks.YOUTUBE_SEARCH.setup + (AppTasks.YOUTUBE_SEARCH.rehearsal as Rehearsal.Steps).steps) assertEquals(SetupResult.Ready, step.apply(scope))
        assertTrue(bench.adb.commands.last { it.startsWith("am start") }.endsWith("-p $revanced"))
        assertTrue(AppTasks.YOUTUBE_SEARCH.check.all { it.passes(scope, HookAnswer.EMPTY) })
        assertTrue(bench.run.restores.runAll().isEmpty())
    }

    @Test
    fun photoLatestPassesInTheViewerAndEachRehearsalPasses() {
        val bench = TaskBench()
        val media = FakeMedia(bench.adb)
        bench.adb.on("^date \\+%Y-%m-%dT%H:%M:%S$") { "2026-11-23T13:05:30" }
        bench.adb.on("^pm list packages ") { "package:${Gallery.PHOTOS}" }
        var front = "com.google.android.apps.nexuslauncher"
        // The activity in front, when it is not the app's main one: the viewer that a VIEW intent opens.
        var activity: String? = null
        bench.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 ${activity ?: "$front/.Main"} t4}" }
        bench.adb.on("^am start -W -a android.intent.action.VIEW -d content://media/external/images/media/") {
            front = Gallery.PHOTOS
            activity = "${Gallery.PHOTOS}/.pager.HostPhotoPagerActivity"
            ""
        }
        bench.adb.on("^input keyevent KEYCODE_HOME$") {
            front = "com.google.android.apps.nexuslauncher"
            activity = null
            ""
        }
        bench.adb.hook("SCREEN_HAS") { extras -> "count=${if (front == Gallery.PHOTOS && extras["contains"] == "1:04") 1 else 0} app=$front" }
        bench.adb.hook("AGENT_TREE") { "tree=written path=cache/agent-tree.json windows=1 elements=1" }
        // Kestrel's tap on the newest photo opens it in Photos' own activity, where it covers the window.
        val viewer = """{"windows":[{"id":4,"kind":"APPLICATION","packageName":"${Gallery.PHOTOS}","active":true,"bounds":[0,0,1080,2400]}],
            "elements":[{"windowId":4,"className":"android.widget.ImageView","bounds":[0,200,1080,2000]}]}"""
        bench.adb.on("^run-as dev\\.kestrel cat cache/agent-tree\\.json$") { if (front == Gallery.PHOTOS) viewer else """{"windows":[],"elements":[]}""" }
        var works = true
        bench.adb.hook("VOICE") {
            if (works) front = Gallery.PHOTOS
            "sent"
        }

        assertEquals(Reason.OK, bench.run(AppTasks.PHOTO_LATEST).reason)
        works = false
        assertEquals(Reason.CHECK, bench.run(AppTasks.PHOTO_LATEST).reason)
        bench.assertPutBack()
        assertEquals(0, media.test.size)

        val firefox = FakeFirefox(bench.adb)
        for (task in AppTasks.ALL) {
            val steps = (task.rehearsal as Rehearsal.Steps).steps
            val scope = bench.run.scope
            bench.adb.on("^pm list packages ") { "package:org.mozilla.firefox\npackage:com.google.android.youtube\npackage:${AppTasks.MAPS}\npackage:${Gallery.PHOTOS}" }
            bench.adb.on("^am start -W -a android.intent.action.VIEW -d '(\\S+)' -p (\\S+)$") { match ->
                front = match.groupValues[2]
                activity = null
                if (front == "org.mozilla.firefox") firefox.tabs.add(FakeFirefox.GITHUB)
                ""
            }
            bench.adb.hook("SCREEN_HAS") { extras ->
                val words = extras["contains"].orEmpty()
                val count = when {
                    front == "org.mozilla.firefox" -> firefox.has(extras)
                    else -> if (words in setOf("kestrel hovering", "Kalemegdan", "1:04")) 1 else 0
                }
                "count=$count app=$front"
            }
            bench.adb.on("^monkey -p org\\.mozilla\\.firefox ") {
                front = "org.mozilla.firefox"
                firefox.tray = false
                ""
            }
            for (step in task.setup + steps) assertEquals(task.name, SetupResult.Ready, step.apply(scope))
            assertTrue(task.name, task.check.all { it.passes(scope, HookAnswer.EMPTY) })
            assertTrue(task.name, bench.run.restores.runAll().isEmpty())
        }
        assertEquals(listOf("Wikipedia, Common kestrel"), firefox.tabs)
        assertEquals(0, media.test.size)
    }
}
