package dev.kestrel.eval.device

import java.time.Instant
import java.time.ZoneId

/** One alarm of `dumpsys alarm`: its type (0 and 1 are wall-clock times), its time, its due time on the elapsed clock, and its app. */
data class AlarmEntry(val type: Int, val origWhen: Long, val whenElapsed: Long, val packageName: String)

/** `dumpsys alarm`'s wall clock in ms ([nowRtc]), its elapsed clock ([nowElapsed]) and its alarms. */
data class AlarmDump(val nowRtc: Long, val nowElapsed: Long, val alarms: List<AlarmEntry>) {
    fun of(packageName: String): List<AlarmEntry> = alarms.filter { it.packageName == packageName }
}

/**
 * The reader of `dumpsys alarm` (design D8 of sub-project 6), the same on Android 13 and 17: the line
 * `nowRTC=<ms>=<date> nowELAPSED=<ms>` and each alarm's `Alarm{<hash> type <n> origWhen <ms> whenElapsed <ms> <package>}`.
 * It keeps only times, types and packages, never an alarm's label.
 */
object Alarms {
    private val clocks = Regex("\\bnowRTC=(\\d+).*?\\bnowELAPSED=(\\d+)")
    private val alarm = Regex("\\bAlarm\\{\\S+ type (\\d+) origWhen (-?\\d+) whenElapsed (-?\\d+) ([\\w.]+)\\}")

    /** The clocks and the alarms, each once, or null when the dump gives no clocks. */
    fun parse(dump: List<String>): AlarmDump? {
        val now = dump.firstNotNullOfOrNull { clocks.find(it) } ?: return null
        val alarms = dump.mapNotNull { line ->
            alarm.find(line)?.destructured?.let { (type, origWhen, whenElapsed, packageName) -> AlarmEntry(type.toInt(), origWhen.toLong(), whenElapsed.toLong(), packageName) }
        }.distinct()
        return AlarmDump(now.groupValues[1].toLong(), now.groupValues[2].toLong(), alarms)
    }

    /** The wall-clock time in ms of the next [hour]:[minute] after [nowRtc] in [zone]. */
    fun nextAt(nowRtc: Long, zone: ZoneId, hour: Int, minute: Int): Long {
        val now = Instant.ofEpochMilli(nowRtc).atZone(zone)
        val today = now.toLocalDate().atTime(hour, minute).atZone(zone)
        val next = if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
        return next.toInstant().toEpochMilli()
    }
}
