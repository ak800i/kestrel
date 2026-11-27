package dev.kestrel.eval.device

import java.time.Instant
import java.time.ZoneId

/**
 * One alarm of `dumpsys alarm`: its type (0 and 1 are wall-clock times), its time, its due time on the elapsed clock, its
 * app, whether it is an alarm clock ([clock]: its entry holds "Alarm clock:", as one set with `setAlarmClock` does),
 * unlike the clock app's other wakeups, such as Google Clock's UPDATE_ALARM_INSTANCES every 15 s around an alarm, and
 * the dump's name of the alarm ([id], the hash in `Alarm{<hash> …}`), which tells two alarms at the same time apart.
 */
data class AlarmEntry(val type: Int, val origWhen: Long, val whenElapsed: Long, val packageName: String, val clock: Boolean = false, val id: String = "")

/** `dumpsys alarm`'s wall clock in ms ([nowRtc]), its elapsed clock ([nowElapsed]) and its alarms. */
data class AlarmDump(val nowRtc: Long, val nowElapsed: Long, val alarms: List<AlarmEntry>) {
    fun of(packageName: String): List<AlarmEntry> = alarms.filter { it.packageName == packageName }
}

/**
 * The reader of `dumpsys alarm` (design D8 of sub-project 6), the same on Android 13 and 17: the line
 * `nowRTC=<ms>=<date> nowELAPSED=<ms>` and each alarm's `Alarm{<hash> type <n> origWhen <ms> whenElapsed <ms> <package>}`,
 * whose own lines, indented below it, hold `Alarm clock:` for an alarm clock. It keeps only times, types, packages and
 * that flag, never an alarm's label.
 */
object Alarms {
    private val clocks = Regex("\\bnowRTC=(\\d+).*?\\bnowELAPSED=(\\d+)")
    private val alarm = Regex("\\bAlarm\\{(\\S+) type (\\d+) origWhen (-?\\d+) whenElapsed (-?\\d+) ([\\w.]+)\\}")

    /**
     * The clocks and the alarms, each once by its [AlarmEntry.id], since the dump lists an alarm in several places, such as
     * its batch and "Next wake from idle", or null when the dump gives no clocks.
     */
    fun parse(dump: List<String>): AlarmDump? {
        val now = dump.firstNotNullOfOrNull { clocks.find(it) } ?: return null
        val alarms = mutableListOf<AlarmEntry>()
        // The alarm whose entry the lines belong to, and its line's indent: a line indented no further ends the entry.
        var open: Int? = null
        var indent = 0
        for (line in dump) {
            val depth = line.length - line.trimStart().length
            val found = alarm.find(line)?.destructured
            if (found != null) {
                val (id, type, origWhen, whenElapsed, packageName) = found
                alarms.add(AlarmEntry(type.toInt(), origWhen.toLong(), whenElapsed.toLong(), packageName, id = id))
                open = alarms.lastIndex
                indent = depth
                continue
            }
            if (line.isBlank()) continue
            val at = open ?: continue
            if (depth <= indent) {
                open = null
            } else if (line.trim() == "Alarm clock:") {
                alarms[at] = alarms[at].copy(clock = true)
            }
        }
        // One entry per alarm: an alarm clock is one where any of its places says so.
        val each = alarms.groupBy { it.id }.values.map { same -> same.first().copy(clock = same.any { it.clock }) }
        return AlarmDump(now.groupValues[1].toLong(), now.groupValues[2].toLong(), each)
    }

    /** The wall-clock time in ms of the next [hour]:[minute] after [nowRtc] in [zone]. */
    fun nextAt(nowRtc: Long, zone: ZoneId, hour: Int, minute: Int): Long {
        val now = Instant.ofEpochMilli(nowRtc).atZone(zone)
        val today = now.toLocalDate().atTime(hour, minute).atZone(zone)
        val next = if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(hour, minute).atZone(zone)
        return next.toInstant().toEpochMilli()
    }
}
