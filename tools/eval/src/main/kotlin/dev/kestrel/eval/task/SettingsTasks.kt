package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import kotlin.time.Duration.Companion.seconds

/**
 * The suite's settings tasks (design D8 of sub-project 6), on both devices; brightness and two-settings are untestable on
 * the A71 ([AospBrightnessCurve]). Each starts on the home screen with Settings stopped and ends there again. A rehearsal
 * makes the end state with one shell command; battery-level's check is Kestrel's reply, so it rehearses its setup only.
 */
object SettingsTasks {
    private val BOTH = setOf(DeviceKind.EMULATOR, DeviceKind.A71)
    private val START = listOf(HomeScreen, ForceStop(Hotspots.SETTINGS))
    private val NIGHT = Shell("cmd uimode night yes", "cmd uimode night no")
    private val DIM = Shell("settings put system screen_brightness 9", "settings put system screen_brightness 200")

    private fun task(name: String, request: String, setup: List<Setup>, check: List<Check>, rehearsal: Rehearsal, guards: List<Setup> = emptyList()) = Task(
        name = name,
        kind = TaskKind.SUITE,
        request = Request(request),
        devices = BOTH,
        answers = Answers.NONE,
        verdict = VerdictRule.Finished,
        setup = guards + START + setup,
        check = check,
        dryRun = DryRun("$name.json"),
        rehearsal = rehearsal,
    )

    val DARK_MODE = task("dark-mode", "turn on dark mode", listOf(UiNightMode(on = false)), listOf(NightMode(on = true)), Rehearsal.Steps(listOf(NIGHT)))

    val BRIGHTNESS = task(
        "brightness", "set the brightness to 30 percent", listOf(ManualBrightness()), listOf(BrightnessNear(30, 5)), Rehearsal.Steps(listOf(DIM)),
        guards = listOf(AospBrightnessCurve),
    )

    val SCREEN_TIMEOUT = task(
        "screen-timeout", "set the screen timeout to 2 minutes",
        listOf(SettingsValue("system", "screen_off_timeout", "30000")),
        listOf(SettingsIs("system", "screen_off_timeout", Wanted.Equals("120000"))),
        Rehearsal.Steps(listOf(Shell("settings put system screen_off_timeout 120000", "settings put system screen_off_timeout 30000"))),
    )

    val DND = task(
        "dnd", "turn on do not disturb", listOf(Dnd(on = false)), listOf(SettingsIs("global", Dnd.ZEN, Wanted.NotEquals("0"))),
        Rehearsal.Steps(listOf(Shell("cmd notification set_dnd priority", "cmd notification set_dnd off"))),
    )

    val BLUETOOTH_OFF = task(
        "bluetooth-off", "turn off Bluetooth", listOf(Bluetooth(on = true)), listOf(SettingsIs("global", "bluetooth_on", Wanted.Equals("0"))),
        Rehearsal.Steps(listOf(Shell("cmd bluetooth_manager disable", "cmd bluetooth_manager enable", settle = 5.seconds))),
    )

    val FONT_SIZE = task(
        "font-size", "make the text bigger",
        listOf(SettingsValue("system", "font_scale", "1.0")),
        listOf(SettingsIs("system", "font_scale", Wanted.Above(1.0))),
        Rehearsal.Steps(listOf(Shell("settings put system font_scale 1.15", "settings put system font_scale 1.0"))),
    )

    val WIFI_SETTINGS = task(
        "wifi-settings", "open the Wi-Fi settings", listOf(WifiPage), listOf(WifiPageShown),
        Rehearsal.Steps(listOf(Shell("am start -W -a android.settings.WIFI_SETTINGS", "am force-stop ${Hotspots.SETTINGS}", settle = 2.seconds))),
    )

    val BATTERY_LEVEL = task(
        "battery-level", "what's my battery level?", listOf(BatteryLevel), listOf(ReplyHasBattery), Rehearsal.SetupOnly("its check reads Kestrel's spoken reply"),
    )

    val TWO_SETTINGS = task(
        "two-settings", "turn on dark mode and set the brightness to 30 percent",
        listOf(UiNightMode(on = false), ManualBrightness()),
        listOf(NightMode(on = true), BrightnessNear(30, 5)),
        Rehearsal.Steps(listOf(NIGHT, DIM)),
        guards = listOf(AospBrightnessCurve),
    )

    val ALL = listOf(DARK_MODE, BRIGHTNESS, SCREEN_TIMEOUT, DND, BLUETOOTH_OFF, FONT_SIZE, WIFI_SETTINGS, BATTERY_LEVEL, TWO_SETTINGS)
}
