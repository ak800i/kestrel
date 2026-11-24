package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The settings tasks against a scripted device that passes and one that fails, with every restore run (design D4 and D8 of sub-project 6). */
class SettingsTasksTest {
    /** kestrel-api37 whose settings Kestrel's task changes as the request asks, while [works]. */
    private class Phone {
        val bench = TaskBench()
        var works = true
        var night = "no"
        var front = LAUNCHER

        init {
            val adb = bench.adb
            adb.settings.putAll(
                mapOf(
                    "global/zen_mode" to "0", "global/bluetooth_on" to "1", "system/screen_brightness" to "102", "system/screen_brightness_mode" to "0",
                    "system/screen_off_timeout" to "2147483647", "system/font_scale" to "1.0",
                ),
            )
            adb.on("^cmd uimode night$") { "Night mode: $night" }
            adb.on("^cmd uimode night (yes|no|auto)$") {
                night = it.groupValues[1]
                ""
            }
            adb.on("^cmd notification set_dnd (\\w+)$") {
                adb.settings["global/zen_mode"] = if (it.groupValues[1] == "off") "0" else "1"
                ""
            }
            adb.on("^dumpsys notification --zen$") { ZenDump.manual(adb.settings["global/zen_mode"] != "0") }
            ZenDump.clock(adb, 12, 0)
            adb.on("^cmd bluetooth_manager (enable|disable)$") {
                adb.settings["global/bluetooth_on"] = if (it.groupValues[1] == "enable") "1" else "0"
                ""
            }
            adb.on("^dumpsys display$") { "  mBrightnessMin=0.0\n  mBrightnessMax=1.0" }
            adb.on("^dumpsys battery$") { "  level: 85" }
            adb.on("^am start -W -a android.settings.WIFI_SETTINGS$") {
                front = WIFI
                ""
            }
            adb.on("^input keyevent KEYCODE_HOME$") {
                front = LAUNCHER
                ""
            }
            adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{a1 u0 $front t3}" }
            adb.hook("SCREEN_HAS") { "count=1 app=com.android.settings" }
            adb.hook("VOICE") { extras ->
                if (works) {
                    when (extras["text"]) {
                        "Kestrel, turn on dark mode" -> night = "yes"
                        "Kestrel, set the brightness to 30 percent" -> adb.settings["system/screen_brightness"] = "9"
                        "Kestrel, turn on dark mode and set the brightness to 30 percent" -> {
                            night = "yes"
                            adb.settings["system/screen_brightness"] = "9"
                        }
                        "Kestrel, set the screen timeout to 2 minutes" -> adb.settings["system/screen_off_timeout"] = "120000"
                        "Kestrel, turn on do not disturb" -> adb.settings["global/zen_mode"] = "1"
                        "Kestrel, turn off Bluetooth" -> adb.settings["global/bluetooth_on"] = "0"
                        "Kestrel, make the text bigger" -> adb.settings["system/font_scale"] = "1.15"
                        "Kestrel, open the Wi-Fi settings" -> front = WIFI
                    }
                }
                "sent"
            }
        }
    }

    @Test
    fun eachSettingsTaskPassesWhenTheSettingChangedFailsWhenItDidNotAndPutsTheSettingsBack() {
        assertTrue(SettingsTasks.ALL.all { it.kind == TaskKind.SUITE && it.devices == setOf(DeviceKind.EMULATOR, DeviceKind.A71) && it.answers == Answers.NONE })
        for (task in SettingsTasks.ALL - SettingsTasks.BATTERY_LEVEL) {
            val phone = Phone()
            val before = phone.bench.adb.settings.toMap()

            assertEquals(task.name, Reason.OK, phone.bench.run(task).reason)
            phone.works = false
            assertEquals(task.name, Reason.CHECK, phone.bench.run(task).reason)
            phone.bench.assertPutBack()
            assertEquals(task.name, before, phone.bench.adb.settings.toMap())
            assertEquals(task.name, "no", phone.night)
            assertEquals(task.name, LAUNCHER, phone.front)
        }
    }

    @Test
    fun batteryLevelPassesWhenTheReplyHoldsTheLevel() {
        val phone = Phone()
        var digits = "1"
        phone.bench.adb.hook("AGENT_TASK") { extras -> if (extras["digits"] != null) "state=done digits=$digits" else phone.bench.end }

        assertEquals(Reason.OK, phone.bench.run(SettingsTasks.BATTERY_LEVEL).reason)
        assertEquals("85", phone.bench.adb.hookCalls("AGENT_TASK").first { it["digits"] != null }["digits"])
        digits = "0"
        assertEquals(Reason.CHECK, phone.bench.run(SettingsTasks.BATTERY_LEVEL).reason)
        phone.bench.assertPutBack()
    }

    @Test
    fun eachRehearsalMakesTheEndStateThatTheChecksPassAndTheRestoresPutBack() {
        for (task in SettingsTasks.ALL) {
            val steps = (task.rehearsal as? Rehearsal.Steps)?.steps ?: continue
            val phone = Phone()
            val before = phone.bench.adb.settings.toMap()
            val scope = phone.bench.run.scope

            for (step in task.setup + steps) assertEquals(task.name, SetupResult.Ready, step.apply(scope))
            assertTrue(task.name, task.check.all { it.passes(scope, HookAnswer.EMPTY) })
            assertTrue(task.name, phone.bench.run.restores.runAll().isEmpty())
            assertEquals(task.name, before, phone.bench.adb.settings.toMap())
            assertEquals(task.name, "no", phone.night)
        }
        assertTrue(SettingsTasks.BATTERY_LEVEL.rehearsal is Rehearsal.SetupOnly)
    }

    private companion object {
        const val LAUNCHER = "com.google.android.apps.nexuslauncher/.NexusLauncherActivity"
        const val WIFI = "com.android.settings/.Settings\$WifiSettingsActivity"
    }
}
