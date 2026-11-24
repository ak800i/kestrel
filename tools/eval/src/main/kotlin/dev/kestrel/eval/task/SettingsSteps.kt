package dev.kestrel.eval.task

import dev.kestrel.eval.device.Brightness
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A setting's value as `settings get` gives it, "null" when the device has none. */
internal fun RunScope.setting(namespace: String, key: String): String = device.read("settings get $namespace $key").joinToString("").trim()

/** What a setting must hold after the run: exactly a value, anything but a value, or a number above one. */
sealed interface Wanted {
    fun accepts(value: String): Boolean

    data class Equals(val value: String) : Wanted {
        override fun accepts(value: String): Boolean = value == this.value
    }

    data class NotEquals(val value: String) : Wanted {
        override fun accepts(value: String): Boolean = value != "null" && value != this.value
    }

    data class Above(val value: Double) : Wanted {
        override fun accepts(value: String): Boolean = (value.toDoubleOrNull() ?: return false) > this.value
    }
}

/**
 * One setting for the run (`settings put`), after a restore that puts back the value read before it, or deletes the
 * setting again when the device had none ("null").
 */
data class SettingsValue(val namespace: String, val key: String, val value: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val before = scope.setting(namespace, key)
        val undo = if (before == "null") "settings delete $namespace $key" else "settings put $namespace $key $before"
        val byHand = "Run: adb -s ${scope.device.serial} shell $undo"
        scope.restores.push("$namespace $key as before", byHand) { scope.device.idempotent(undo) }
        scope.restores.change("$namespace $key set to $value", byHand) { scope.device.idempotent("settings put $namespace $key $value") }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("$namespace/$key" to scope.setting(namespace, key))
}

/** The setting holds what the task asked for. */
data class SettingsIs(val namespace: String, val key: String, val wanted: Wanted) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = wanted.accepts(scope.setting(namespace, key))
}

/** Dark theme's mode from `cmd uimode night`, which answers "Night mode: yes". */
object Night {
    /** The modes that `cmd uimode night <mode>` puts back as they were; a custom schedule is not among them. */
    val MODES = setOf("yes", "no", "auto")
    private val ANSWER = Regex("^Night mode: (\\S+)")

    fun mode(scope: RunScope): String = scope.device.read("cmd uimode night").firstNotNullOfOrNull { ANSWER.find(it.trim())?.groupValues?.get(1) } ?: "unknown"
}

/**
 * Dark theme on or off for the run, after a restore that puts back the mode read before it. A mode that the shell cannot
 * put back as it was, such as a custom schedule, makes the task untestable on the device.
 */
data class UiNightMode(val on: Boolean) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val before = Night.mode(scope)
        if (before !in Night.MODES) return SetupResult.Untestable("its dark theme follows a schedule that the shell cannot put back")
        val byHand = "Run: adb -s ${scope.device.serial} shell cmd uimode night $before"
        scope.restores.push("dark theme as before", byHand) { scope.device.idempotent("cmd uimode night $before") }
        scope.restores.change("dark theme ${if (on) "on" else "off"}", byHand) { scope.device.idempotent("cmd uimode night ${if (on) "yes" else "no"}") }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("night" to Night.mode(scope))
}

/** Dark theme is on, or off. */
data class NightMode(val on: Boolean) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean = Night.mode(scope) == if (on) "yes" else "no"
}

/**
 * Automatic brightness off and `screen_brightness` at [setting] for the run (design D8 of sub-project 6; 200 is 96 % on
 * AOSP's slider and 78 % on a linear one, far from the 30 % that the task asks for), after a restore that puts back
 * both values read before it, the brightness before its mode.
 */
data class ManualBrightness(val setting: Int = 200) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val mode = scope.setting("system", MODE)
        val level = scope.setting("system", LEVEL)
        val undo = listOfNotNull(
            level.takeIf { it != "null" }?.let { "settings put system $LEVEL $it" },
            mode.takeIf { it != "null" }?.let { "settings put system $MODE $it" },
        )
        val byHand = "Run: adb -s ${scope.device.serial} shell \"${undo.joinToString("; ")}\""
        scope.restores.push("the brightness as before", byHand) { undo.forEach { scope.device.idempotent(it) } }
        scope.restores.change("the brightness set by hand", byHand) {
            scope.device.idempotent("settings put system $MODE 0")
            scope.device.idempotent("settings put system $LEVEL $setting")
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf(MODE to scope.setting("system", MODE), LEVEL to scope.setting("system", LEVEL))

    companion object {
        const val MODE = "screen_brightness_mode"
        const val LEVEL = "screen_brightness"
    }
}

/** The brightness slider shows [percent] within [tolerance] points, on AOSP's curve with the device's range ([Brightness]). */
data class BrightnessNear(val percent: Int, val tolerance: Int) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val level = scope.setting("system", ManualBrightness.LEVEL).toIntOrNull() ?: return false
        return abs(Brightness.percent(level, Brightness.range(scope.device.read("dumpsys display"))) - percent) <= tolerance
    }
}

/**
 * Bluetooth on or off for the run (`cmd bluetooth_manager enable|disable`), after a restore that puts back the state
 * read before it from `bluetooth_on`; each waits up to 10 s for the setting to follow. A device whose Bluetooth does not
 * follow makes the task untestable there.
 */
data class Bluetooth(val on: Boolean) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val before = state(scope) ?: return SetupResult.Untestable("it does not say whether Bluetooth is on")
        val byHand = "Settings > Connected devices: turn Bluetooth ${word(before)}."
        scope.restores.push("Bluetooth as before", byHand) { check(turn(scope, before)) { "Bluetooth did not turn ${word(before)} again." } }
        val done = scope.restores.change("Bluetooth turned ${word(on)}", byHand) { turn(scope, on) }
        return if (done) SetupResult.Ready else SetupResult.Untestable("Bluetooth does not turn ${word(on)} here")
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("bluetooth" to (state(scope)?.toString() ?: "unknown"))

    companion object {
        /** Whether Bluetooth is on (`bluetooth_on` 1, or 2 when only its low-energy part runs), or null when the device does not say. */
        fun state(scope: RunScope): Boolean? = when (scope.setting("global", "bluetooth_on")) {
            "0" -> false
            "1", "2" -> true
            else -> null
        }

        /** Turns Bluetooth [on] unless it already is, and waits up to 10 s; whether it then is. */
        fun turn(scope: RunScope, on: Boolean): Boolean {
            if (state(scope) == on) return true
            scope.device.idempotent("cmd bluetooth_manager ${if (on) "enable" else "disable"}")
            repeat(10) {
                scope.clock.sleep(1.seconds)
                if (state(scope) == on) return true
            }
            return false
        }

        private fun word(on: Boolean) = if (on) "on" else "off"
    }
}

/**
 * Do Not Disturb on (priority only) or off for the run (`cmd notification set_dnd`), after a restore that puts back the
 * mode read before it from `zen_mode`: 0 off, 1 priority only, 2 total silence, 3 alarms only. A device whose Do Not
 * Disturb is on already cannot run the task: a schedule may have turned it on, and `set_dnd` would put it back as a
 * manual Do Not Disturb without an end.
 */
data class Dnd(val on: Boolean) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val before = NAMES[scope.setting("global", ZEN)] ?: return SetupResult.Untestable("it does not say its Do Not Disturb mode")
        if (before != "off") return SetupResult.Untestable("Do Not Disturb is on already, perhaps by a schedule, which the shell cannot put back as it was")
        val byHand = "Run: adb -s ${scope.device.serial} shell cmd notification set_dnd $before"
        scope.restores.push("Do Not Disturb as before", byHand) { scope.device.idempotent("cmd notification set_dnd $before") }
        scope.restores.change("Do Not Disturb ${if (on) "on" else "off"}", byHand) {
            scope.device.idempotent("cmd notification set_dnd ${if (on) "priority" else "off"}")
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf(ZEN to scope.setting("global", ZEN))

    companion object {
        const val ZEN = "zen_mode"
        private val NAMES = mapOf("0" to "off", "1" to "priority", "2" to "none", "3" to "alarms")
    }
}

/**
 * A rehearsal's end state made by one shell command, such as `cmd uimode night yes`, after a restore that runs [undo];
 * the task's setup restores, which run after it, then put back the owner's value. [settle] lets the device follow.
 */
data class Shell(val command: String, val undo: String, val settle: Duration = Duration.ZERO) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val byHand = "Run: adb -s ${scope.device.serial} shell $undo"
        scope.restores.push("'$undo' run", byHand) { scope.device.idempotent(undo) }
        scope.restores.change("'$command' run", byHand) { scope.device.idempotent(command) }
        if (settle > Duration.ZERO) scope.clock.sleep(settle)
        return SetupResult.Ready
    }
}

/** The home screen before the run, and again after it, so that a page or a dialog that the task opened does not stay in front. */
data object HomeScreen : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("the home screen in front", "Press Home.") { scope.device.idempotent("input keyevent KEYCODE_HOME") }
        scope.device.idempotent("input keyevent KEYCODE_HOME")
        return SetupResult.Ready
    }
}

/**
 * wifi-settings' calibration (design D8 of sub-project 6): Settings' Wi-Fi page opened by its intent, its activity and
 * the first of its switch's labels that `screen-has` counts recorded in [RunScope.notes], and the home screen again. A
 * device without both makes the task untestable there.
 */
data object WifiPage : Setup {
    const val ACTIVITY = "wifi-activity"
    const val SWITCH = "wifi-switch"

    /** The page's own switch: One UI's and older AOSP's "Wi-Fi", AOSP's "Use Wi-Fi", and AOSP's "Wi‑Fi" with a non-breaking hyphen. */
    val SWITCHES = listOf("Use Wi-Fi", "Wi-Fi", "Wi\u2011Fi")

    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("Settings stopped", "Close Settings.") { scope.device.idempotent("am force-stop ${Hotspots.SETTINGS}") }
        scope.restores.change("the Wi-Fi settings opened", "Close Settings.") { scope.device.idempotent("am start -W -a android.settings.WIFI_SETTINGS") }
        scope.clock.sleep(2.seconds)
        val activity = Dumps.resumedActivity(scope.device.read("dumpsys activity activities"))
        val switch = SWITCHES.firstOrNull { scope.hooks.screenHas(packageName = Hotspots.SETTINGS, label = it).int("count") >= 1 }
        scope.device.idempotent("input keyevent KEYCODE_HOME")
        if (activity == null || switch == null) return SetupResult.Untestable("Settings shows no Wi-Fi page with its switch here")
        scope.notes[ACTIVITY] = activity
        scope.notes[SWITCH] = switch
        return SetupResult.Ready
    }
}

/**
 * The Wi-Fi page is in front: the activity that [WifiPage] recorded, or Settings with the page's switch, because a page
 * reached through Settings' own menus can show in `SubSettings`.
 */
data object WifiPageShown : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val activity = scope.notes[WifiPage.ACTIVITY] ?: return false
        val dump = scope.device.read("dumpsys activity activities")
        if (Dumps.resumedActivity(dump) == activity) return true
        val switch = scope.notes[WifiPage.SWITCH] ?: return false
        return Dumps.resumedPackage(dump) == Hotspots.SETTINGS && scope.hooks.screenHas(packageName = Hotspots.SETTINGS, label = switch).int("count") >= 1
    }
}

/** The battery's level from `dumpsys battery` in `{number}`, which the dry run's script says; it changes nothing. */
data object BatteryLevel : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val level = Dumps.batteryLevel(scope.device.read("dumpsys battery")) ?: return SetupResult.Untestable("dumpsys battery gives no level")
        scope.values.number = "$level"
        return SetupResult.Ready
    }
}

/** Kestrel's reply holds the battery's level read at the setup, or the level now, because it may have changed by one. */
data object ReplyHasBattery : Check {
    override val needsTask: Boolean get() = true

    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val tag = scope.tag ?: return false
        val levels = listOfNotNull(scope.values.number, Dumps.batteryLevel(scope.device.read("dumpsys battery"))?.toString()).distinct()
        return levels.any { scope.hooks.agentTask(tag, digits = it)["digits"] == "1" }
    }
}
