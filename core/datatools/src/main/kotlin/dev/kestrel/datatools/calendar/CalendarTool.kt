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
 * `list_events` and `create_event` (design D12 of sub-project 5): the events of the owner's calendars between two local
 * days, at most [MAX], and the calendar app's own insert screen for a new event, where the agent taps Save. [granted]
 * tells whether Kestrel holds a permission.
 */
class CalendarTool(private val resolver: ContentResolver, private val granted: (String) -> Boolean) {
    suspend fun list(from: LocalDate, to: LocalDate, zone: ZoneId): DataResult {
        if (!granted(Manifest.permission.READ_CALENDAR)) return DataResult.NeedsPermission(listOf(Manifest.permission.READ_CALENDAR))
        val start = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, start)
            ContentUris.appendId(it, end)
        }.build()
        val lines = withContext(Dispatchers.IO) {
            val found = mutableListOf<DataLine>()
            resolver.query(uri, COLUMNS, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { rows ->
                while (found.size < MAX && rows.moveToNext()) {
                    found += DataLines.event(rows.getString(0), rows.getLong(1), rows.getLong(2), rows.getInt(3) != 0, rows.getString(4), rows.getString(5), zone)
                }
            }
            found
        }
        return DataResult.Ok(lines)
    }

    companion object {
        const val MAX = 20

        private val COLUMNS = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

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
