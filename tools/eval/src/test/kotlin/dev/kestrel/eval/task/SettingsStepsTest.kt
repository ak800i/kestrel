package dev.kestrel.eval.task

import dev.kestrel.eval.device.Brightness
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

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
        ZenDump.clock(run.adb, 12, 0)

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
        ZenDump.clock(run.adb, 12, 0)
        run.adb.on("^cmd notification set_dnd (\\w+)$") {
            run.adb.settings["global/zen_mode"] = if (it.groupValues[1] == "off") "0" else "1"
            dump = ZenDump.manual(it.groupValues[1] != "off")
            ""
        }
        // A run whose task left Do Not Disturb on as [atTheEnd] shows it; the restore's own set_dnd commands, and [failed] restores.
        fun restoreAfter(atTheEnd: String, failed: Int = 0): List<String> {
            run.adb.settings["global/zen_mode"] = "0"
            dump = ZenDump.manual(false)
            assertEquals(SetupResult.Ready, Dnd(on = false).apply(run.scope))
            run.adb.settings["global/zen_mode"] = "1"
            dump = atTheEnd
            val from = run.adb.commands.size
            val failures = run.restores.runAll()
            assertEquals(failed, failures.size)
            run.restores.report(failures)
            return run.adb.commands.drop(from).filter { it.startsWith("cmd notification") }
        }

        // Kestrel turns Do Not Disturb on through Settings or the Quick Settings tile, which leave no enabler or name
        // Settings or System UI; a rehearsal's set_dnd names the shell. Each goes off again.
        for (enabler in listOf(null, "", "com.android.settings", "com.android.systemui", "com.android.shell", "dev.kestrel")) {
            assertEquals("enabler $enabler", listOf("cmd notification set_dnd off"), restoreAfter(ZenDump.manual(true, enabler = enabler)))
            assertEquals("0", run.adb.settings["global/zen_mode"])
        }
        assertEquals(listOf("cmd notification set_dnd off"), restoreAfter(ZenDump.modesUi(ZenDump.ruleUi(null, "STATE_TRUE"), ZenDump.ruleUi("a", null))))
        assertTrue(run.console.warnings.isEmpty())

        // A routine or an app of the owner's turned the manual Do Not Disturb on during the run: it stays, and the
        // restore fails, so that the by-hand command shows.
        assertEquals(emptyList<String>(), restoreAfter(ZenDump.manual(true, enabler = "com.samsung.android.app.routines"), failed = 1))
        assertEquals("1", run.adb.settings["global/zen_mode"])
        assertEquals(1, run.console.warnings.size)
        assertTrue(run.console.warnings.single().startsWith("Not put back: Do Not Disturb as before"))
        assertTrue(run.console.warnings.single().contains("cmd notification set_dnd off"))
        run.console.warnings.clear()

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
    fun doNotDisturbIsUntestableWhenAnAutomaticRuleCouldStartDuringTheRun() {
        val run = FakeRun(DeviceKind.A71)
        run.adb.settings["global/zen_mode"] = "0"
        var dump = ""
        run.adb.on("^dumpsys notification --zen$") { dump }
        run.adb.on("^cmd notification set_dnd (\\w+)$") { "" }
        // Dnd's setup on [zen] at [hour]:[minute]: Ready or Untestable, and on Untestable no restore and no set_dnd.
        fun ready(zen: String, hour: Int, minute: Int, days: Long = 0): Boolean {
            dump = zen
            ZenDump.clock(run.adb, hour, minute, days)
            val from = run.adb.commands.size
            val result = Dnd(on = false).apply(run.scope)
            if (result is SetupResult.Untestable) {
                assertEquals(0, run.restores.size)
                assertTrue(run.adb.commands.drop(from).none { it.startsWith("cmd notification set_dnd") })
            }
            assertTrue(run.restores.runAll().isEmpty())
            return result == SetupResult.Ready
        }
        val nightly = ZenDump.android13(null, ZenDump.rule13("EVERY_NIGHT_DEFAULT_RULE", null))

        assertTrue(ready(nightly, 12, 0))
        assertFalse(ready(nightly, 21, 45))
        assertFalse(ready(nightly, 21, 30))
        assertTrue(ready(nightly, 21, 29))
        // A schedule that is turned off, or that the owner snoozed tonight, after it began, does not start again.
        assertTrue(ready(ZenDump.android13(null, ZenDump.rule13("a", null, enabled = false)), 21, 45))
        assertTrue(ready(ZenDump.android13(null, ZenDump.rule13("a", "STATE_TRUE", snoozing = true)), 23, 0))
        assertFalse(ready(ZenDump.modesUi(ZenDump.ruleUi(null, null), ZenDump.ruleUi("a", null)), 21, 45))
        assertTrue(ready(ZenDump.modesUi(ZenDump.ruleUi(null, null), ZenDump.ruleUi("a", null)), 12, 0))

        // Tuesday's 00:10 starts within 30 minutes of Monday's 23:50; Wednesday's does not.
        val tuesday = ZenDump.android13(null, ZenDump.rule13("a", null, conditionId = "condition://android/schedule?days=3&start=0.10&end=6.0"))
        assertFalse(ready(tuesday, 23, 50))
        assertTrue(ready(tuesday, 23, 50, days = 1))

        // An event rule, an app's rule and a schedule that this cannot read could start at any time.
        assertFalse(ready(ZenDump.android13(null, ZenDump.rule13("a", null, conditionId = ZenDump.EVENTS)), 12, 0))
        assertFalse(ready(ZenDump.android13(null, ZenDump.rule13("a", null, conditionId = "condition://com.example.focus/mode?id=1")), 12, 0))
        assertFalse(ready(ZenDump.android13(null, ZenDump.rule13("a", null, conditionId = "condition://android/schedule?days=&start=22.0&end=7.0")), 12, 0))

        // A dump that does not say the rules, or a clock that does not say the time, makes the task untestable too.
        assertFalse(ready("  Notification listeners:", 12, 0))
        dump = nightly
        run.adb.props.remove("persist.sys.timezone")
        assertTrue(Dnd(on = false).apply(run.scope) is SetupResult.Untestable)
        run.adb.props["persist.sys.timezone"] = "Europe/Belgrade"
        run.adb.on("^date \\+%s$") { "" }
        assertTrue(Dnd(on = false).apply(run.scope) is SetupResult.Untestable)
        assertEquals(0, run.restores.size)
        // Without an enabled rule, the device's time is not needed.
        dump = ZenDump.manual(false).replace("enabled=TRUE", "enabled=FALSE")
        assertEquals(SetupResult.Ready, Dnd(on = false).apply(run.scope))
        assertTrue(run.restores.runAll().isEmpty())
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
        // With automatic brightness the device changes the level itself, so only the mode is compared.
        assertEquals(mapOf("screen_brightness_mode" to "1"), ManualBrightness().probe(run.scope))
        ManualBrightness().apply(run.scope)
        assertEquals(mapOf("screen_brightness_mode" to "0", "screen_brightness" to "200"), ManualBrightness().probe(run.scope))
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

        // A device without the mode gets none again, rather than manual brightness.
        val bare = FakeRun()
        bare.adb.on("^settings delete system screen_brightness_mode$") { bare.adb.settings.remove("system/screen_brightness_mode"); "" }
        bare.adb.settings["system/screen_brightness"] = "40"
        ManualBrightness().apply(bare.scope)
        bare.restores.runAll()
        assertEquals(mapOf("system/screen_brightness" to "40"), bare.adb.settings.toMap())
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

    @Test
    fun theDisplayDensityThatARunChangedIsAppliedAgainNotOnlyWrittenBack() {
        val run = FakeRun(DeviceKind.A71)

        assertEquals(mapOf(KeepDisplayDensity.NAME to "none"), KeepDisplayDensity.probe(run.scope))
        assertEquals(SetupResult.Ready, KeepDisplayDensity.apply(run.scope))
        // The run's Screen zoom: Android applies it at once and stores it.
        run.adb.density = "480"
        run.adb.settings["secure/display_density_forced"] = "480"

        assertTrue(run.restores.runAll().isEmpty())
        assertNull(run.adb.density)
        assertNull(run.adb.settings["secure/display_density_forced"])
        assertEquals(listOf(KeepDisplayDensity.NAME), run.scope.putBack.applied)

        // The owner's own override comes back as it was.
        run.adb.density = "400"
        assertEquals(SetupResult.Ready, KeepDisplayDensity.apply(run.scope))
        run.adb.density = "480"
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals("400", run.adb.density)
    }

    @Test
    fun theStoredFontKeysThatARunChangedGoBackAndAreNamedForTheHand() {
        val run = FakeRun(DeviceKind.A71)
        run.adb.on("^settings delete (\\S+) (\\S+)$") {
            run.adb.settings.remove("${it.groupValues[1]}/${it.groupValues[2]}")
            ""
        }
        run.adb.settings.putAll(mapOf("system/font_scale" to "1.1", "system/font_size" to "3", "secure/android_id" to "x"))
        val keep = KeepStoredSettings(KeepStoredSettings.DISPLAY_SIZE, except = setOf(SettingKey("system", "font_scale")))

        assertEquals(mapOf("system/font_size" to "3"), keep.probe(run.scope))
        assertEquals(SetupResult.Ready, keep.apply(run.scope))
        // The run changed One UI's font size index and chose a font style, which One UI stores in a key of its own.
        run.adb.settings["system/font_size"] = "5"
        run.adb.settings["system/flip_font_style"] = "2"
        run.adb.settings["system/font_scale"] = "1.3"

        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(mapOf("system/font_scale" to "1.3", "system/font_size" to "3", "secure/android_id" to "x"), run.adb.settings.toMap())
        assertEquals(listOf("system/font_size", "system/flip_font_style"), run.scope.putBack.stored)
        assertTrue(run.console.warnings.toString(), run.console.warnings.any { it.contains("system/font_size, system/flip_font_style") })
    }
}

/**
 * `dumpsys notification --zen` of a test device, as AOSP's ZenModeHelper prints it: [android13] before modes UI (the
 * A71), [modesUi] with it, where ZenModeConfig prints itself over several lines and the manual rule is always there.
 */
internal object ZenDump {
    /** The owner's bedtime schedule, every day from 22:00 to 7:00. */
    const val NIGHTLY = "condition://android/schedule?days=1.2.3.4.5.6.7&start=22.0&end=7.0&exitAtAlarm=true"
    const val EVENTS = "condition://android/event?userId=-10000&calendar=&reply=1"

    private fun rule(conditionId: String, enabler: String?) = "name=Sleeping,zenMode=ZEN_MODE_IMPORTANT_INTERRUPTIONS,conditionId=$conditionId," +
        "pkg=android,component=null,configActivity=null,creationTime=1,enabler=$enabler,zenPolicy=ZenPolicy{priorityCategories=[reminders=disallow ], " +
        "interruptionType=[alarms=all ]},modified=false"

    private fun condition(state: String?, conditionId: String) = state?.let { "Condition[state=$it,id=$conditionId,summary=...,line1=,line2=,icon=0,flags=2]" }

    /** A rule of Android 13, whose condition has [state] (none when null), turned on by [enabler]. */
    fun rule13(id: String?, state: String?, enabled: Boolean = true, snoozing: Boolean = false, conditionId: String = NIGHTLY, enabler: String? = null) =
        "ZenRule[id=$id,state=${state ?: "STATE_FALSE"},enabled=${"$enabled".uppercase()},snoozing=$snoozing,${rule(conditionId, enabler)},condition=${condition(state, conditionId)}]"

    /** A rule with modes UI, whose condition has [state] (none when null), turned on by [enabler]. */
    fun ruleUi(id: String?, state: String?, override: String = "OVERRIDE_NONE", enabled: Boolean = true, conditionId: String = NIGHTLY, enabler: String? = null) =
        "ZenRule[id=$id,state=${state ?: "STATE_FALSE"},enabled=${"$enabled".uppercase()},conditionOverride=$override,${rule(conditionId, enabler)}," +
            "condition=${condition(state, conditionId)},deviceEffects=null,allowManualInvocation=true,iconResName=null,triggerDescription=null,type=1]"

    /** The device's clock at [hour]:[minute] on Monday 23 November 2026 plus [days], in Belgrade, as `date +%s` and `persist.sys.timezone` give it. */
    fun clock(adb: FakeAdb, hour: Int, minute: Int, days: Long = 0) {
        adb.props["persist.sys.timezone"] = "Europe/Belgrade"
        val now = ZonedDateTime.of(2026, 11, 23, hour, minute, 0, 0, ZoneId.of("Europe/Belgrade")).plusDays(days)
        adb.on("^date \\+%s$") { "${now.toEpochSecond()}" }
    }

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

    /**
     * Do Not Disturb [on] by the manual rule as `set_dnd` leaves it on Android 13 (turned on by [enabler], the shell's
     * package for `set_dnd`), or off, beside a schedule that is off.
     */
    fun manual(on: Boolean, enabler: String? = "com.android.shell") =
        android13(if (on) rule13(null, null, enabler = enabler) else null, rule13("EVERY_NIGHT_DEFAULT_RULE", null))
}
