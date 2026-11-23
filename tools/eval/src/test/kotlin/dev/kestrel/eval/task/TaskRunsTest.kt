package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeMedia
import dev.kestrel.eval.run.Reason
import dev.kestrel.eval.run.RunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each D3 task and stand-in against a scripted device that passes and one that fails, with every restore run (design D4 of sub-project 6). */
class TaskRunsTest {
    private val config = LocalConfig.of(mapOf("gmailAddress" to "pera.test@example.com", "emulatorGmailAddress" to "pera.second@example.com", "testLocation" to "44.8100,20.4600"))

    private fun fixtureScreen(bench: TaskBench, count: (Map<String, String>) -> Int) {
        bench.adb.hook("SCREEN_HAS") { extras -> "count=${count(extras)} app=dev.kestrel.testapp" }
    }

    @Test
    fun standInHotspot() {
        val bench = TaskBench()
        var checked = 1
        fixtureScreen(bench) { if (it["label"] == "Wi-Fi hotspot" && it["state"] == "checked") checked else 0 }

        assertEquals(Reason.OK, bench.run(StandIns.HOTSPOT).reason)
        checked = 0
        assertEquals(Reason.CHECK, bench.run(StandIns.HOTSPOT).reason)
        bench.assertPutBack()
        assertTrue(bench.adb.commands.contains("am start -W -S -n dev.kestrel.testapp/.StandInActivity --es screen 'hotspot'"))
        assertEquals(
            listOf("am force-stop dev.kestrel.testapp", "run-as dev.kestrel.testapp rm -f files/presses.log"),
            bench.adb.commands.filter { it.startsWith("am force-stop") || it.startsWith("run-as dev.kestrel.testapp rm") }.takeLast(2),
        )
    }

    @Test
    fun standInFirefox() {
        val bench = TaskBench()
        var shown = 1
        fixtureScreen(bench) { if (it["contains"] == "Common kestrel") shown else 0 }

        assertEquals(RunResult.PASS, bench.run(StandIns.FIREFOX).result)
        shown = 0
        assertEquals(RunResult.FAIL, bench.run(StandIns.FIREFOX).result)
        bench.assertPutBack()
    }

    @Test
    fun standInRead() {
        val bench = TaskBench()

        assertEquals(RunResult.PASS, bench.run(StandIns.READ).result)
        assertTrue(bench.adb.commands.contains("am start -W -S -n dev.kestrel.testapp/.StandInActivity --es screen 'inbox' --es marker 'maple heron'"))
        bench.replyMarker = "0"
        assertEquals(Reason.CHECK, bench.run(StandIns.READ).reason)
        bench.assertPutBack()
    }

    @Test
    fun standInMessengerLateInBothForms() {
        for (task in listOf(StandIns.MESSENGER_LATE, StandIns.MESSENGER_LATE_REWORDED)) {
            val bench = TaskBench()
            var presses = "send 1795086000000"
            bench.adb.on("^run-as dev.kestrel.testapp sh -c") { presses }
            fixtureScreen(bench) { if (it["contains"] == "maple heron") 1 else 0 }
            bench.end = "state=done end=FINISHED steps=5 credits=0.00 confirmed=2 unconfirmed=0 shareItems=0 beforeTestMode=0"

            assertEquals(task.name, RunResult.PASS, bench.run(task).result)
            assertEquals(mapOf("selfChatAliases" to "dev.kestrel.testapp=org.telegram.messenger"), bench.adb.hookCalls("AGENT_SETTINGS").first())
            assertEquals("Ana", bench.adb.hookCalls("AGENT_MEMORY").first { it["add"] == "true" }["key"])
            presses = ""
            assertEquals(task.name, Reason.CHECK, bench.run(task).reason)
            bench.assertPutBack()
        }
    }

    @Test
    fun standInMessengerPhotosIsAKnownGapThatSharesNothing() {
        val bench = TaskBench()
        val media = FakeMedia(bench.adb)
        bench.end = "state=done end=TEST_MODE steps=4 credits=0.00 confirmed=1 unconfirmed=0 shareItems=0 beforeTestMode=0"

        assertEquals(RunResult.PASS, bench.run(StandIns.MESSENGER_PHOTOS).result)
        assertEquals(0, media.test.size)
        bench.end = "state=done end=FINISHED steps=6 credits=0.00 confirmed=2 unconfirmed=0 shareItems=3 beforeTestMode=0"
        assertEquals(Reason.END, bench.run(StandIns.MESSENGER_PHOTOS).reason)
        bench.assertPutBack()
        assertTrue(bench.adb.commands.contains("pm revoke dev.kestrel android.permission.READ_MEDIA_IMAGES"))
    }

    @Test
    fun hotspotLive() {
        val bench = TaskBench(live = true)
        var on = false
        bench.adb.on("^dumpsys tethering$") { if (on) "  wlan1 - TetheredState" else "  wlan1 - AvailableState" }
        bench.adb.on("^dumpsys connectivity$") { "  Capabilities: INTERNET&VALIDATED Transports: WIFI" }
        bench.adb.hook("SCREEN_HAS") { "count=1 app=com.android.settings" }
        bench.adb.hook("VOICE") { extras -> if (extras["text"] == "Kestrel, turn on hotspot") on = true; "sent" }
        bench.adb.on("^cmd wifi stop-softap$") { on = false; "" }

        assertEquals(RunResult.PASS, bench.run(D3Tasks.HOTSPOT).result)
        assertTrue(!on)
        bench.adb.hook("VOICE") { "sent" }
        assertEquals(Reason.CHECK, bench.run(D3Tasks.HOTSPOT).reason)
        bench.assertPutBack()
    }

    @Test
    fun firefoxWikiLiveOnBothDevices() {
        for (kind in DeviceKind.entries) {
            val bench = TaskBench(kind, live = true)
            var front = "org.mozilla.firefox"
            bench.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{9f3e2b1 u0 $front/.App t12}" }
            bench.adb.hook("SCREEN_HAS") { extras -> if (extras["contains"] == "Common kestrel") "count=2 app=$front" else "count=0 app=$front" }

            assertEquals(RunResult.PASS, bench.run(D3Tasks.FIREFOX_WIKI).result)
            front = "com.android.chrome"
            assertEquals(Reason.CHECK, bench.run(D3Tasks.FIREFOX_WIKI).reason)
            bench.assertPutBack()
        }
    }

    @Test
    fun readEmailLiveSkipsWithoutAnAddress() {
        val bench = TaskBench(config = config, live = true)
        bench.adb.hook("SCREEN_TAP") { "tapped=true count=1" }
        bench.adb.hook("SCREEN_HAS") { "count=1 app=com.google.android.gm" }

        assertEquals(RunResult.PASS, bench.run(D3Tasks.READ_EMAIL).result)
        bench.replyMarker = "0"
        assertEquals(Reason.CHECK, bench.run(D3Tasks.READ_EMAIL).reason)
        assertEquals(Reason.UNTESTABLE, TaskBench(live = true).run(D3Tasks.READ_EMAIL).reason)
        bench.assertPutBack()
    }

    @Test
    fun telegramLateOnTheA71Only() {
        val bench = TaskBench(DeviceKind.A71, live = true)
        bench.adb.on("^pm list packages org.telegram.messenger.web$") { "package:org.telegram.messenger.web" }
        bench.adb.hook("SCREEN_HAS") { extras ->
            when (extras["contains"]) {
                "Ana" -> "count=1 app=org.telegram.messenger.web"
                "maple heron" -> "count=1 app=org.telegram.messenger.web"
                else -> "count=0 app=org.telegram.messenger.web"
            }
        }
        bench.end = "state=done end=FINISHED steps=6 credits=1.25 confirmed=2 unconfirmed=0 shareItems=0 beforeTestMode=0 model=gpt-6-luna"

        assertEquals(RunResult.PASS, bench.run(D3Tasks.TELEGRAM_LATE).result)
        assertEquals("Mira", bench.adb.hookCalls("AGENT_MEMORY").first { it["add"] == "true" }["key"])
        assertEquals("Kestrel, send Mira a Telegram message that I'm 10 minutes late for maple heron", bench.adb.hookCalls("VOICE").first()["text"])
        bench.recipient = "other"
        assertEquals(Reason.CHECK, bench.run(D3Tasks.TELEGRAM_LATE).reason)
        bench.assertPutBack()
        assertEquals(Reason.UNTESTABLE, TaskBench(live = true).run(D3Tasks.TELEGRAM_LATE).reason)
    }

    @Test
    fun telegramPhotosIsAKnownGapOnTheA71() {
        val bench = TaskBench(DeviceKind.A71, live = true)
        FakeMedia(bench.adb)
        bench.adb.on("^pm list packages org.telegram.messenger.web$") { "package:org.telegram.messenger.web" }
        bench.adb.hook("SCREEN_HAS") { "count=0 app=org.telegram.messenger.web" }
        bench.end = "state=done end=TEST_MODE steps=4 credits=0.80 confirmed=1 unconfirmed=0 shareItems=0 beforeTestMode=0"

        assertEquals(RunResult.PASS, bench.run(D3Tasks.TELEGRAM_PHOTOS).result)
        bench.end = "state=done end=TEST_MODE steps=4 credits=0.80 confirmed=1 unconfirmed=1 shareItems=0 beforeTestMode=1"
        assertEquals(RunResult.FAIL, bench.run(D3Tasks.TELEGRAM_PHOTOS).result)
        bench.assertPutBack()
    }

    @Test
    fun wazeHomeOnTheA71AtItsTestLocation() {
        val bench = TaskBench(DeviceKind.A71, config = config, live = true)
        var navigating = false
        bench.adb.on("^pm list packages com.waze$") { "package:com.waze" }
        bench.adb.on("^am start -a android.intent.action.VIEW -d 'waze://") { navigating = true; "" }
        bench.adb.on("^am force-stop com.waze$") { navigating = false; "" }
        bench.adb.on("^dumpsys notification$") { if (navigating) "  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7)\n    flags=0x62" else "" }
        bench.adb.hook("VOICE") { extras -> if (extras["text"] == "Kestrel, start Waze navigation to home") navigating = true; "sent" }
        val fixes = mutableListOf<String>()
        // The real task's steps, with a sender of fixes that only records, because the fake device is not shared between threads.
        val task = D3Tasks.WAZE_HOME.copy(setup = listOf(TestLocation { _: Device, at: String -> fixes.add(at); AutoCloseable { fixes.add("stop") } }, WazeHome, ForceStop(WazeHome.WAZE)))
        assertEquals(D3Tasks.WAZE_HOME.setup.map { it::class }, task.setup.map { it::class })

        assertEquals(RunResult.PASS, bench.run(task).result)
        assertEquals(listOf("44.8100,20.4600", "stop"), fixes)
        bench.adb.hook("VOICE") { "sent" }
        assertEquals(Reason.CHECK, bench.run(task).reason)
        bench.assertPutBack()
    }
}
