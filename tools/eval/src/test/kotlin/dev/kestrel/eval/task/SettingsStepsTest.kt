package dev.kestrel.eval.task

import dev.kestrel.eval.device.Brightness
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings tasks' steps and checks against a scripted device (design D4 and D8 of sub-project 6). */
class SettingsStepsTest {
    private val none = HookAnswer.EMPTY

    @Test
    fun aSettingGetsTheOwnersValueBackOrLeavesAgainWhenItHadNone() {
        val run = FakeRun()
        run.adb.on("^settings delete (\\S+) (\\S+)$") {
            run.adb.settings.remove("${it.groupValues[1]}/${it.groupValues[2]}")
            ""
        }
        run.adb.settings["system/screen_off_timeout"] = "60000"
        val timeout = SettingsValue("system", "screen_off_timeout", "30000")

        assertEquals(mapOf("system/screen_off_timeout" to "60000"), timeout.probe(run.scope))
        assertEquals(SetupResult.Ready, timeout.apply(run.scope))
        assertEquals("30000", run.adb.settings["system/screen_off_timeout"])
        assertFalse(SettingsIs("system", "screen_off_timeout", Wanted.Equals("120000")).passes(run.scope, none))
        run.adb.settings["system/screen_off_timeout"] = "120000"
        assertTrue(SettingsIs("system", "screen_off_timeout", Wanted.Equals("120000")).passes(run.scope, none))
        SettingsValue("system", "font_scale", "1.0").apply(run.scope)
        assertFalse(SettingsIs("system", "font_scale", Wanted.Above(1.0)).passes(run.scope, none))
        run.adb.settings["system/font_scale"] = "1.15"
        assertTrue(SettingsIs("system", "font_scale", Wanted.Above(1.0)).passes(run.scope, none))

        run.restores.runAll()
        assertEquals("60000", run.adb.settings["system/screen_off_timeout"])
        assertNull(run.adb.settings["system/font_scale"])
    }

    @Test
    fun darkThemeAndDoNotDisturbGoBackToTheOwnersModes() {
        val run = FakeRun(DeviceKind.A71)
        var night = "custom_schedule"
        run.adb.on("^cmd uimode night$") { "Night mode: $night" }
        run.adb.on("^cmd uimode night (yes|no|auto)$") {
            night = it.groupValues[1]
            "Night mode: $night"
        }
        val modes = mapOf("off" to "0", "priority" to "1", "none" to "2", "alarms" to "3")
        run.adb.on("^cmd notification set_dnd (\\w+)$") {
            run.adb.settings["global/zen_mode"] = modes.getValue(it.groupValues[1])
            ""
        }
        run.adb.settings["global/zen_mode"] = "3"

        assertTrue(UiNightMode(on = false).apply(run.scope) is SetupResult.Untestable)
        assertEquals(0, run.restores.size)
        // Do Not Disturb that is on already, perhaps by the owner's schedule, cannot be put back as it was.
        assertTrue(Dnd(on = false).apply(run.scope) is SetupResult.Untestable)
        assertEquals(0, run.restores.size)
        run.adb.settings["global/zen_mode"] = "0"
        night = "yes"
        assertEquals(mapOf("night" to "yes"), UiNightMode(on = false).probe(run.scope))
        UiNightMode(on = false).apply(run.scope)
        Dnd(on = false).apply(run.scope)
        assertEquals("no", night)
        assertEquals("0", run.adb.settings["global/zen_mode"])
        assertFalse(NightMode(on = true).passes(run.scope, none))
        assertFalse(SettingsIs("global", "zen_mode", Wanted.NotEquals("0")).passes(run.scope, none))
        night = "yes"
        run.adb.settings["global/zen_mode"] = "1"
        assertTrue(NightMode(on = true).passes(run.scope, none))
        assertTrue(SettingsIs("global", "zen_mode", Wanted.NotEquals("0")).passes(run.scope, none))

        night = "no"
        run.restores.runAll()
        assertEquals("yes", night)
        assertEquals("0", run.adb.settings["global/zen_mode"])
        assertEquals("cmd notification set_dnd off", run.adb.commands.last { it.startsWith("cmd notification") })
    }

    @Test
    fun theBrightnessSlidersPercentFollowsAospsGammaCurve() {
        val emulator = listOf("  mBrightnessMin=0.0", "  mBrightnessMax=1.0")
        val samsung = listOf("    mScreenBrightnessRangeMinimum=0.0039215687", "    mScreenBrightnessRangeMaximum=1.0")
        assertEquals(0.0..1.0, Brightness.range(emulator))
        assertEquals(0.0039215687, Brightness.range(samsung).start, 1e-9)
        assertEquals(0.0..1.0, Brightness.range(listOf("  mScreenBrightnessRangeMinimum=1", "  mScreenBrightnessRangeMaximum=255")))
        assertEquals(listOf(0, 11, 27, 31, 34, 83, 96, 100), listOf(1, 2, 7, 9, 11, 102, 200, 255).map { Brightness.percent(it, 0.0..1.0) })

        val run = FakeRun()
        run.adb.on("^dumpsys display$") { emulator.joinToString("\n") }
        run.adb.settings["system/screen_brightness_mode"] = "1"
        run.adb.settings["system/screen_brightness"] = "7"
        assertEquals(mapOf("screen_brightness_mode" to "1", "screen_brightness" to "7"), ManualBrightness().probe(run.scope))
        ManualBrightness().apply(run.scope)
        assertEquals("0", run.adb.settings["system/screen_brightness_mode"])
        assertEquals("200", run.adb.settings["system/screen_brightness"])
        assertFalse(BrightnessNear(30, 5).passes(run.scope, none))
        run.adb.settings["system/screen_brightness"] = "9"
        assertTrue(BrightnessNear(30, 5).passes(run.scope, none))
        run.adb.settings["system/screen_brightness"] = "12"
        assertFalse(BrightnessNear(30, 5).passes(run.scope, none))

        run.restores.runAll()
        assertEquals("7", run.adb.settings["system/screen_brightness"])
        assertEquals("1", run.adb.settings["system/screen_brightness_mode"])
        assertEquals(
            listOf("settings put system screen_brightness 7", "settings put system screen_brightness_mode 1"),
            run.adb.commands.filter { it.startsWith("settings put") }.takeLast(2),
        )
    }

    @Test
    fun bluetoothTurnsOnForTheRunAndBackOffOrTheTaskIsUntestable() {
        val run = FakeRun()
        var works = true
        run.adb.settings["global/bluetooth_on"] = "0"
        run.adb.on("^cmd bluetooth_manager (enable|disable)$") {
            if (works) run.adb.settings["global/bluetooth_on"] = if (it.groupValues[1] == "enable") "1" else "0"
            ""
        }

        assertEquals(mapOf("bluetooth" to "false"), Bluetooth(on = true).probe(run.scope))
        assertEquals(SetupResult.Ready, Bluetooth(on = true).apply(run.scope))
        assertEquals("1", run.adb.settings["global/bluetooth_on"])
        run.restores.runAll()
        assertEquals("0", run.adb.settings["global/bluetooth_on"])

        works = false
        assertTrue(Bluetooth(on = true).apply(run.scope) is SetupResult.Untestable)
        assertTrue(run.restores.runAll().isEmpty())
    }

    @Test
    fun theWifiPageIsItsActivityOrSettingsWithThePagesSwitch() {
        val run = FakeRun()
        var front = "com.android.settings/.Settings\$WifiSettingsActivity"
        run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1b2c3 u0 $front t12}" }
        var switches = 1
        run.adb.hook("SCREEN_HAS") { extras -> "count=${if (extras["label"] == "Wi\u2011Fi") switches else 0} app=com.android.settings" }

        assertEquals("com.android.settings/.Settings\$WifiSettingsActivity", Dumps.resumedActivity(run.device.read("dumpsys activity activities")))
        assertEquals(SetupResult.Ready, WifiPage.apply(run.scope))
        assertEquals(front, run.scope.notes[WifiPage.ACTIVITY])
        assertEquals("Wi\u2011Fi", run.scope.notes[WifiPage.SWITCH])
        assertTrue(run.adb.commands.contains("input keyevent KEYCODE_HOME"))
        assertTrue(WifiPageShown.passes(run.scope, none))
        front = "com.android.settings/.SubSettings"
        assertTrue(WifiPageShown.passes(run.scope, none))
        switches = 0
        assertFalse(WifiPageShown.passes(run.scope, none))
        front = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"
        switches = 1
        assertFalse(WifiPageShown.passes(run.scope, none))

        run.restores.runAll()
        assertEquals("am force-stop com.android.settings", run.adb.commands.last())
    }

    @Test
    fun theBatteryLevelGoesIntoTheRunsNumberAndTheReplyMayHoldItOrTheLevelNow() {
        val run = FakeRun()
        var level = 85
        run.adb.on("^dumpsys battery$") { "Current Battery Service state:\n  AC powered: true\n  level: $level\n  Capacity level: -1\n  scale: 100" }
        val asked = mutableListOf<String>()
        run.adb.hook("AGENT_TASK") { extras ->
            extras["digits"]?.let { asked.add(it) }
            if (extras["digits"] == "84" || extras["digits"] == "2025550143") "state=done digits=1" else "state=done digits=0"
        }

        assertEquals(85, Dumps.batteryLevel(run.device.read("dumpsys battery")))
        assertEquals(SetupResult.Ready, BatteryLevel.apply(run.scope))
        assertEquals("85", run.scope.values.number)
        assertFalse(ReplyHasBattery.passes(run.scope, none))
        run.scope.tag = "t1"
        assertFalse(ReplyHasBattery.passes(run.scope, none))
        level = 84
        assertTrue(ReplyHasBattery.passes(run.scope, none))
        assertEquals(listOf("85", "85", "84"), asked)
        assertFalse(ReplyHasDigits("{number}").passes(run.scope, none))
        assertTrue(ReplyHasDigits("2025550143").passes(run.scope, none))
    }
}
