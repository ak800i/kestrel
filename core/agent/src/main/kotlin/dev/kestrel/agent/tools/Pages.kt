package dev.kestrel.agent.tools

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * The settings pages that `open_settings` opens (design D11 of sub-project 5): each page's intent actions, tried in
 * order until one has an activity. The protected pages (accessibility, security, device admin, unknown sources,
 * biometrics, lock screen) are not on the list (spec 12.3's floor).
 */
enum class SettingsPage(val wire: String, val actions: List<String>) {
    WIFI("wifi", listOf("android.settings.WIFI_SETTINGS")),
    BLUETOOTH("bluetooth", listOf("android.settings.BLUETOOTH_SETTINGS")),
    HOTSPOT("hotspot", listOf("android.settings.TETHER_SETTINGS", "com.android.settings.WIFI_TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS")),
    MOBILE_DATA("mobile_data", listOf("android.settings.DATA_USAGE_SETTINGS")),
    DISPLAY("display", listOf("android.settings.DISPLAY_SETTINGS")),
    SOUND("sound", listOf("android.settings.SOUND_SETTINGS")),
    LOCATION("location", listOf("android.settings.LOCATION_SOURCE_SETTINGS")),
    BATTERY("battery", listOf("android.settings.BATTERY_SAVER_SETTINGS")),
    DATE_TIME("date_time", listOf("android.settings.DATE_SETTINGS")),
    APPS("apps", listOf("android.settings.APPLICATION_SETTINGS")),
    NOTIFICATIONS("notifications", listOf("android.settings.ALL_APPS_NOTIFICATION_SETTINGS", "android.settings.APPLICATION_SETTINGS")),
    MAIN("main", listOf("android.settings.SETTINGS")),
    ;

    companion object {
        fun fromWire(name: String): SettingsPage? = entries.firstOrNull { it.wire == name }
    }
}

/** `open_url`'s browsers (design D11): the packages to try, the first installed one wins; [DEFAULT] sets none. */
enum class Browser(val wire: String, val packages: List<String>) {
    DEFAULT("default", emptyList()),
    FIREFOX("firefox", listOf("org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix")),
    CHROME("chrome", listOf("com.android.chrome")),
    ;

    companion object {
        fun fromWire(name: String): Browser? = entries.firstOrNull { it.wire == name }
    }
}

/**
 * The share targets that `share_media` names by a word (design D12): the packages to try, in order, the first installed
 * one wins; empty for a launcher app's name. Telegram has its Play build and its website build, which the A71 runs
 * (review 3 I2).
 */
object SharePackages {
    private val KNOWN = mapOf(
        "telegram" to listOf("org.telegram.messenger", "org.telegram.messenger.web"),
        "whatsapp" to listOf("com.whatsapp"),
        "viber" to listOf("com.viber.voip"),
        "gmail" to listOf("com.google.android.gm"),
    )

    fun of(app: String): List<String> = KNOWN[app.trim().lowercase()].orEmpty()
}

/** The data tools' days and times (design D12): `today`, `yesterday`, `YYYY-MM-DD` and `YYYY-MM-DDTHH:MM`, local time. */
object Dates {
    private val DAY = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val DATE_TIME = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}")

    fun day(text: String, today: LocalDate): LocalDate? = when (val word = text.trim().lowercase()) {
        "today" -> today
        "yesterday" -> today.minusDays(1)
        else -> if (DAY.matches(word)) parse { LocalDate.parse(word) } else null
    }

    fun dateTime(text: String): LocalDateTime? {
        val trimmed = text.trim()
        return if (DATE_TIME.matches(trimmed)) parse { LocalDateTime.parse(trimmed) } else null
    }

    private fun <T> parse(block: () -> T): T? = try {
        block()
    } catch (_: DateTimeParseException) {
        null
    }
}
