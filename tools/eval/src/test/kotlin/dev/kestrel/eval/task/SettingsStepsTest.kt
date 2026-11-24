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
        run.adb.on("^dumpsys notification --zen$") { ZenDump.manual(run.adb.settings["global/zen_mode"] != "0") }

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
    fun theZenDumpSaysWhetherTheManualDoNotDisturbOrAnAutomaticRuleIsOn() {
        fun on(dump: String) = Zen.on(dump.lines())

        assertEquals(Zen.On(manual = true, automatic = false), on(ZenDump.manual(true)))
        assertEquals(Zen.On(manual = false, automatic = false), on(ZenDump.manual(false)))
        assertEquals(Zen.On(manual = false, automatic = true), on(ZenDump.android13(null, ZenDump.rule13("a", "STATE_TRUE"))))
        // A schedule that the owner snoozed, or turned off, is not on.
        assertEquals(
            Zen.On(manual = false, automatic = false),
            on(ZenDump.android13(null, ZenDump.rule13("a", "STATE_TRUE", snoozing = true), ZenDump.rule13("b", "STATE_TRUE", enabled = false))),
        )
        // With modes UI the manual rule is always there, and a deleted rule is never on.
        assertEquals(
            Zen.On(manual = false, automatic = false),
            on(ZenDump.modesUi(ZenDump.ruleUi(null, null), ZenDump.ruleUi("a", "STATE_FALSE"), deleted = ZenDump.ruleUi("d", "STATE_TRUE"))),
        )
        assertEquals(Zen.On(manual = true, automatic = false), on(ZenDump.modesUi(ZenDump.ruleUi(null, "STATE_TRUE"), ZenDump.ruleUi("a", "STATE_TRUE", "OVERRIDE_DEACTIVATE"))))
        assertEquals(Zen.On(manual = false, automatic = true), on(ZenDump.modesUi(ZenDump.ruleUi(null, null), ZenDump.ruleUi("a", null, "OVERRIDE_ACTIVATE"))))
        assertNull(on("  Notification listeners:"))
    }

    @Test
    fun doNotDisturbsRestoreTurnsOffOnlyTheManualOneAndNeverAScheduleThatBeganDuringTheRun() {
        val run = FakeRun(DeviceKind.A71)
        run.adb.settings["global/zen_mode"] = "0"
        var dump = ZenDump.manual(false)
        run.adb.on("^dumpsys notification --zen$") { dump }
        run.adb.on("^cmd notification set_dnd (\\w+)$") {
            run.adb.settings["global/zen_mode"] = if (it.groupValues[1] == "off") "0" else "1"
            dump = ZenDump.manual(it.groupValues[1] != "off")
            ""
        }
        // A run whose task left Do Not Disturb on as [atTheEnd] shows it; the restore's own set_dnd commands.
        fun restoreAfter(atTheEnd: String): List<String> {
            run.adb.settings["global/zen_mode"] = "0"
            dump = ZenDump.manual(false)
            assertEquals(SetupResult.Ready, Dnd(on = false).apply(run.scope))
            run.adb.settings["global/zen_mode"] = "1"
            dump = atTheEnd
            val from = run.adb.commands.size
            assertTrue(run.restores.runAll().isEmpty())
            return run.adb.commands.drop(from).filter { it.startsWith("cmd notification") }
        }

        // Kestrel's manual Do Not Disturb goes off again.
        assertEquals(listOf("cmd notification set_dnd off"), restoreAfter(ZenDump.manual(true)))
        assertEquals("0", run.adb.settings["global/zen_mode"])

        // The owner's schedule began during the run: set_dnd off would snooze it for the night, so both stay on, with a word.
        val schedule = ZenDump.rule13("EVERY_NIGHT_DEFAULT_RULE", "STATE_TRUE")
        assertEquals(emptyList<String>(), restoreAfter(ZenDump.android13(ZenDump.rule13(null, null), schedule)))
        assertEquals("1", run.adb.settings["global/zen_mode"])
        assertEquals(1, run.console.warnings.size)

        // A schedule alone is left as it is, without a word.
        assertEquals(emptyList<String>(), restoreAfter(ZenDump.android13(null, schedule)))
        assertEquals(1, run.console.warnings.size)

        // A dump that does not say which Do Not Disturb is on changes nothing, and the restore fails.
        run.adb.settings["global/zen_mode"] = "0"
        Dnd(on = false).apply(run.scope)
        run.adb.settings["global/zen_mode"] = "1"
        dump = "  Notification listeners:"
        val from = run.adb.commands.size
        assertEquals(1, run.restores.runAll().size)
        assertEquals("1", run.adb.settings["global/zen_mode"])
        assertTrue(run.adb.commands.drop(from).none { it.startsWith("cmd notification") })
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

/**
 * `dumpsys notification --zen` of a test device, as AOSP's ZenModeHelper prints it: [android13] before modes UI (the
 * A71), [modesUi] with it, where ZenModeConfig prints itself over several lines and the manual rule is always there.
 */
internal object ZenDump {
    private const val RULE = "name=Sleeping,zenMode=ZEN_MODE_IMPORTANT_INTERRUPTIONS,conditionId=condition://android/schedule?days=1.2.3.4.5.6.7&start=22.0&end=7.0," +
        "pkg=android,component=null,configActivity=null,creationTime=1,enabler=null,zenPolicy=null,modified=false"

    private fun condition(state: String?) = state?.let { "Condition[state=$it,id=condition://android/schedule,summary=...,line1=,line2=,icon=0,flags=2]" }

    /** A rule of Android 13, whose condition has [state] (none when null). */
    fun rule13(id: String?, state: String?, enabled: Boolean = true, snoozing: Boolean = false) =
        "ZenRule[id=$id,state=${state ?: "STATE_FALSE"},enabled=${"$enabled".uppercase()},snoozing=$snoozing,$RULE,condition=${condition(state)}]"

    /** A rule with modes UI, whose condition has [state] (none when null). */
    fun ruleUi(id: String?, state: String?, override: String = "OVERRIDE_NONE", enabled: Boolean = true) =
        "ZenRule[id=$id,state=${state ?: "STATE_FALSE"},enabled=${"$enabled".uppercase()},conditionOverride=$override,$RULE,condition=${condition(state)}," +
            "deviceEffects=null,allowManualInvocation=true,iconResName=null,triggerDescription=null,type=1]"

    fun android13(manual: String?, vararg automatic: String): String {
        val config = (
            listOf(
                "allow(alarms=true,media=true,system=false,calls=true,callsFrom=stars,repeatCallers=true,messages=false,messagesFrom=contacts,conversations=true," +
                    "conversationsFrom=important,events=false,reminders=false)",
                "      disallow(visualEffects=157)",
                "      manualRule=$manual",
            ) + automatic.mapIndexed { index, rule -> (if (index == 0) "      automaticRules=" else "                     ") + rule }
            ).joinToString("\n")
        return "    mZenMode=ZEN_MODE_OFF\n    mConsolidatedPolicy=Policy[]\n    mConfigs[u=0]=$config\n    mUser=0\n    mConfig=$config\n    mSuppressedEffects=0"
    }

    fun modesUi(manual: String, vararg automatic: String, deleted: String? = null): String =
        "    mZenMode=ZEN_MODE_OFF\n    mConfig=ZenModeConfig[user=0\nhasPriorityChannels=false,allowPriorityChannels=true,\nautomaticRules={\n" +
            automatic.joinToString(",\n") { "a=$it" } + "}" + ",\nmanualRule=$manual,\ndeletedRules={${deleted?.let { "\nd=$it" }.orEmpty()}}]\n    mSuppressedEffects=0"

    /** Do Not Disturb [on] by the manual rule as `set_dnd` leaves it on Android 13, or off, beside a schedule that is off. */
    fun manual(on: Boolean) = android13(if (on) rule13(null, null) else null, rule13("EVERY_NIGHT_DEFAULT_RULE", null))
}
