package dev.kestrel.eval.task

import dev.kestrel.eval.device.Brightness
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
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
 * both values read before it, the brightness before its mode. With automatic brightness on, the device changes
 * `screen_brightness` itself (One UI on the A71 does), so the rehearsal's probe compares the brightness only when the
 * mode was manual, and the mode always.
 */
data class ManualBrightness(val setting: Int = 200) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val mode = scope.setting("system", MODE)
        val level = scope.setting("system", LEVEL)
        val undo = listOfNotNull(
            level.takeIf { it != "null" }?.let { "settings put system $LEVEL $it" },
            if (mode == "null") "settings delete system $MODE" else "settings put system $MODE $mode",
        )
        val byHand = "Run: adb -s ${scope.device.serial} shell \"${undo.joinToString("; ")}\""
        scope.restores.push("the brightness as before", byHand) { undo.forEach { scope.device.idempotent(it) } }
        scope.restores.change("the brightness set by hand", byHand) {
            scope.device.idempotent("settings put system $MODE 0")
            scope.device.idempotent("settings put system $LEVEL $setting")
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> {
        val mode = scope.setting("system", MODE)
        return if (mode == AUTOMATIC) mapOf(MODE to mode) else mapOf(MODE to mode, LEVEL to scope.setting("system", LEVEL))
    }

    companion object {
        private const val AUTOMATIC = "1"
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
 * Which Do Not Disturb is on, from `dumpsys notification --zen`, which prints the zen config `mConfig` last: its manual
 * rule (Android 13 prints `null` when it is off; with modes UI it is always there) and its automatic rules, such as the
 * owner's schedules and apps' modes. A rule is on as AOSP's `ZenRule.isActive` says, before modes UI (`snoozing`) and
 * with it (`conditionOverride`); with modes UI the config's deleted rules come last, and are never on.
 */
object Zen {
    data class On(val manual: Boolean, val automatic: Boolean)

    /** What is on, or null when [dump] has no zen config with a manual rule. */
    fun on(dump: List<String>): On? {
        val rules = rules(dump) ?: return null
        return On(rules.any { it.manual && active(it.text, manual = true) }, rules.any { !it.manual && active(it.text, manual = false) })
    }

    /**
     * The `conditionId` of each automatic rule that is not turned off, "" for one that shows none, or null when [dump]
     * has no zen config with a manual rule.
     */
    fun enabledConditions(dump: List<String>): List<String>? =
        rules(dump)?.filter { !it.manual && field(it.text, "enabled") != "FALSE" }?.map { field(it.text, "conditionId").orEmpty() }

    /** The package that turned the manual rule on (its `enabler`), or null when [dump] names none. */
    fun manualEnabler(dump: List<String>): String? = rules(dump)?.firstOrNull { it.manual }?.let { field(it.text, "enabler") }?.takeIf { it != "null" && it.isNotEmpty() }

    private class Rule(val text: String, val manual: Boolean)

    private fun rules(dump: List<String>): List<Rule>? {
        val start = dump.indexOfLast { it.trimStart().startsWith("mConfig=") }
        if (start < 0) return null
        val end = (start + 1 until dump.size).firstOrNull { dump[it].trimStart().startsWith("mSuppressedEffects=") } ?: dump.size
        val config = dump.subList(start, end).joinToString("\n").substringBefore("deletedRules=")
        if (!MANUAL.containsMatchIn(config)) return null
        val starts = Regex.escape(RULE).toRegex().findAll(config).map { it.range.first }.toList()
        return starts.mapIndexed { index, from ->
            Rule(config.substring(from, starts.getOrElse(index + 1) { config.length }), config.substring(0, from).endsWith("manualRule="))
        }
    }

    private fun field(rule: String, name: String) = Regex("[\\[,]$name=([^,\\]]*)").find(rule)?.groupValues?.get(1)

    private fun active(rule: String, manual: Boolean): Boolean {
        fun field(name: String) = field(rule, name)
        val enabled = field("enabled") == "TRUE"
        val trueOrUnknown = field("state") == "STATE_TRUE" || field("state") == "STATE_UNKNOWN"
        return when (field("conditionOverride")) {
            // Before modes UI, a manual rule exists only while it is on.
            null -> manual || (enabled && field("snoozing") == "false" && trueOrUnknown)
            "OVERRIDE_ACTIVATE" -> enabled
            "OVERRIDE_DEACTIVATE" -> false
            else -> enabled && trueOrUnknown
        }
    }

    private const val RULE = "ZenRule["
    private val MANUAL = Regex("manualRule=(null|ZenRule\\[)")
}

/**
 * A Do Not Disturb schedule's days and start, from its `conditionId` as AOSP's `ZenModeConfig.toScheduleConditionId`
 * writes it: `condition://android/schedule?days=1.2.3&start=22.0&end=7.0`, the days as `Calendar`'s (1 is Sunday).
 */
data class ZenSchedule(val days: Set<DayOfWeek>, val start: LocalTime) {
    /** Whether it starts from a minute before [now], when the device may not have turned it on yet, to [window] after it. */
    fun startsWithin(now: ZonedDateTime, window: Duration): Boolean {
        val from = now.minusMinutes(1)
        val to = now.plusSeconds(window.inWholeSeconds)
        return (-1L..1L).map { now.toLocalDate().plusDays(it) }.filter { it.dayOfWeek in days }
            .map { it.atTime(start).atZone(now.zone) }.any { !it.isBefore(from) && !it.isAfter(to) }
    }

    companion object {
        private const val PREFIX = "condition://android/schedule?"

        /** The schedule of [conditionId], or null when it is no schedule or one that this cannot read. */
        fun of(conditionId: String): ZenSchedule? {
            if (!conditionId.startsWith(PREFIX)) return null
            val query = conditionId.removePrefix(PREFIX).split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val days = query["days"]?.split('.')?.map { it.toIntOrNull()?.takeIf { day -> day in 1..7 } ?: return null } ?: return null
            val (hour, minute) = query["start"]?.split('.')?.takeIf { it.size == 2 }?.map { it.toIntOrNull() ?: return null } ?: return null
            if (hour !in 0..23 || minute !in 0..59) return null
            return ZenSchedule(days.map { if (it == 1) DayOfWeek.SUNDAY else DayOfWeek.of(it - 1) }.toSet(), LocalTime.of(hour, minute))
        }
    }
}

/**
 * Do Not Disturb on (priority only) or off for the run (`cmd notification set_dnd`). A device whose Do Not Disturb is on
 * already cannot run the task: a schedule may have turned it on, and `set_dnd` would put it back as a manual Do Not
 * Disturb without an end. Nor can a device with an automatic rule that is not turned off and could start during the run
 * ([SOON]): a schedule that starts within 30 minutes, or any other rule, such as an event rule, whose start this cannot
 * tell; then neither the run's restore nor a rehearsal's `set_dnd off` can leave the owner's rule as it was. When the
 * zen config or the device's time cannot be read, the task is untestable too. The restore turns off a manual Do Not
 * Disturb that the run turned on: one with no enabler or one of [ENABLERS], because Kestrel turns it on through Settings
 * or the Quick Settings tile, and the setup required Do Not Disturb off. One that another app, such as a routine of the
 * owner's, turned on during the run stays, and the restore fails, so that the run says "Not put back:" with the command
 * that turns it off by hand. The restore does so only while no automatic rule is on ([Zen]): `set_dnd off` would
 * snooze a schedule of the owner's that began during the run for the rest of the night, so then the restore leaves Do
 * Not Disturb as it is, and says so when the manual one is on too.
 */
data class Dnd(val on: Boolean) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val before = NAMES[scope.setting("global", ZEN)] ?: return SetupResult.Untestable("it does not say its Do Not Disturb mode")
        if (before != "off") return SetupResult.Untestable("Do Not Disturb is on already, perhaps by a schedule, which the shell cannot put back as it was")
        mayStart(scope)?.let { return SetupResult.Untestable(it) }
        val byHand = "Unless a schedule of the device has Do Not Disturb on, run: adb -s ${scope.device.serial} shell cmd notification set_dnd off"
        scope.restores.push("Do Not Disturb as before", byHand) { turnOff(scope) }
        scope.restores.change("Do Not Disturb ${if (on) "on" else "off"}", byHand) {
            scope.device.idempotent("cmd notification set_dnd ${if (on) "priority" else "off"}")
        }
        return SetupResult.Ready
    }

    private fun turnOff(scope: RunScope) {
        if (scope.setting("global", ZEN) == "0") return
        val dump = scope.device.read("dumpsys notification --zen")
        val zen = Zen.on(dump) ?: throw IllegalStateException("the device did not say which Do Not Disturb is on")
        val enabler = Zen.manualEnabler(dump)
        when {
            zen.automatic && zen.manual -> scope.console.warn(
                "Do Not Disturb: a schedule of the device began during the run, so the run left Do Not Disturb on, also the task's own. " +
                    "When the schedule ends, turn Do Not Disturb off if it stays on.",
            )
            zen.manual && (enabler == null || enabler in ENABLERS) -> scope.device.idempotent("cmd notification set_dnd off")
            zen.manual -> throw IllegalStateException("$enabler turned Do Not Disturb on during the run, not the run itself, so the run left it on")
        }
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf(ZEN to scope.setting("global", ZEN))

    /** Why an automatic rule could start during the run, or null when none can. */
    private fun mayStart(scope: RunScope): String? {
        val conditions = Zen.enabledConditions(scope.device.read("dumpsys notification --zen")) ?: return "it does not say its Do Not Disturb rules"
        if (conditions.isEmpty()) return null
        val schedules = conditions.map { ZenSchedule.of(it) ?: return "an automatic Do Not Disturb rule of it, such as an event rule, could start during the run" }
        val now = deviceNow(scope) ?: return "it does not say its time, so its Do Not Disturb schedules could start during the run"
        return if (schedules.any { it.startsWithin(now, SOON) }) "a Do Not Disturb schedule of it starts within 30 minutes" else null
    }

    /** The device's time in its own zone, or null when it does not say either. */
    private fun deviceNow(scope: RunScope): ZonedDateTime? {
        val seconds = scope.device.read("date +%s").joinToString("").trim().toLongOrNull() ?: return null
        val zone = try {
            ZoneId.of(scope.device.read("getprop persist.sys.timezone").joinToString("").trim())
        } catch (e: DateTimeException) {
            return null
        }
        return Instant.ofEpochSecond(seconds).atZone(zone)
    }

    companion object {
        const val ZEN = "zen_mode"
        private val NAMES = mapOf("0" to "off", "1" to "priority", "2" to "none", "3" to "alarms")
        private val SOON = 30.minutes

        /**
         * The enablers of a manual Do Not Disturb that the restore turns off, besides none: Kestrel turns it on through
         * Settings or the Quick Settings tile, which record no enabler or Settings' or System UI's package, a rehearsal's
         * `set_dnd` records the shell's, and Kestrel's own package stays for an API that it may use later.
         */
        val ENABLERS = setOf("com.android.settings", "com.android.systemui", "com.android.shell", "dev.kestrel")
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
