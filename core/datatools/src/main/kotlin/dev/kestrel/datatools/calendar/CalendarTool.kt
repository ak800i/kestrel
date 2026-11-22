package dev.kestrel.datatools.calendar

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.datatools.DataLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * `list_events` and `create_event` (design D12 of sub-project 5): the events of the owner's visible calendars that cover
 * two local days, the first [MAX] of them with a count when there are more, and the calendar app's own insert screen for
 * a new event, where the agent taps Save. [granted] tells whether Kestrel holds a permission.
 */
class CalendarTool(private val resolver: ContentResolver, private val granted: (String) -> Boolean) {
    suspend fun list(from: LocalDate, to: LocalDate, zone: ZoneId): DataResult {
        if (!granted(Manifest.permission.READ_CALENDAR)) return DataResult.NeedsPermission(listOf(Manifest.permission.READ_CALENDAR))
        val (start, end) = bounds(from, to, zone)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val lines = withContext(Dispatchers.IO) {
            val found = mutableListOf<DataLine>()
            // The provider's range includes its ends and holds all-day events at UTC midnights, so covers() keeps only the
            // events that cover the days.
            resolver.query(uri, COLUMNS, VISIBLE, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { rows ->
                while (rows.moveToNext()) {
                    val begin = rows.getLong(1)
                    val finish = rows.getLong(2)
                    val allDay = rows.getInt(3) != 0
                    if (!covers(begin, finish, allDay, from, to, zone)) continue
                    found += DataLines.event(rows.getString(0), begin, finish, allDay, rows.getString(4), rows.getString(5), zone)
                }
            }
            found
        }
        return result(lines)
    }

    companion object {
        const val MAX = 20

        private const val VISIBLE = "${CalendarContract.Instances.VISIBLE} = 1"

        private val COLUMNS = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        /**
         * Whether an event covers the local days [from] to [to]: an all-day event by its UTC days, a timed one when it
         * overlaps them, so that an event that ends as the first day starts is not listed.
         */
        fun covers(begin: Long, end: Long, allDay: Boolean, from: LocalDate, to: LocalDate, zone: ZoneId): Boolean {
            if (allDay) {
                val days = DataLines.allDayDates(begin, end)
                return days.start <= to && days.endInclusive >= from
            }
            val (start, finish) = bounds(from, to, zone)
            return begin < finish && maxOf(end, begin + 1) > start
        }

        /** `list_events`' result: the first [MAX] events, and a last line without fields with the count when there are more. */
        fun result(found: List<DataLine>): DataResult.Ok {
            val count = DataLine("Kestrel lists the first $MAX of ${found.size} events.", emptyList()).takeIf { found.size > MAX }
            return DataResult.Ok(found.take(MAX) + listOfNotNull(count))
        }

        private fun bounds(from: LocalDate, to: LocalDate, zone: ZoneId): Pair<Long, Long> =
            from.atStartOfDay(zone).toInstant().toEpochMilli() to to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        /** The calendar app's insert screen with the event; without an end, the event lasts an hour. */
        fun insertIntent(title: String, start: LocalDateTime, end: LocalDateTime?, location: String?, zone: ZoneId): Intent {
            val begin = start.atZone(zone).toInstant().toEpochMilli()
            val finish = end?.atZone(zone)?.toInstant()?.toEpochMilli() ?: (begin + 3_600_000)
            return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, finish)
                .putExtra(CalendarContract.Events.TITLE, title)
                .apply { location?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) } }
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
