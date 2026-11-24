package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookAnswer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.seconds

/** [text] as one shell word in single quotes, each quote of it written `'\''`, for a `content` command's `--where` or value. */
internal fun shellQuoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/** The rows of `content query` ("Row: 0 _id=3, title=…") as maps of their columns; a value that holds ", " is cut there. */
object Rows {
    fun parse(lines: List<String>): List<Map<String, String>> = lines.filter { it.startsWith("Row: ") }.map { line ->
        line.removePrefix("Row: ").substringAfter(' ').split(", ").mapNotNull { pair ->
            pair.indexOf('=').takeIf { it > 0 }?.let { pair.substring(0, it) to pair.substring(it + 1) }
        }.toMap()
    }
}

/** The device's time, from `date +%s` and its time zone, which a run's `{date}` and its events follow. */
object DeviceTime {
    fun now(scope: RunScope): ZonedDateTime {
        val seconds = scope.device.read("date +%s").joinToString("").trim().toLongOrNull() ?: throw SetupFailed("The device did not say its time.")
        return Instant.ofEpochSecond(seconds).atZone(ClockApp.zone(scope))
    }

    /** [time] on the run's `{date}` in the device's zone, in ms. */
    fun at(scope: RunScope, time: LocalTime): Long {
        val date = scope.values.date ?: throw IllegalStateException("The run has no value for {date}.")
        return LocalDate.parse(date).atTime(time).atZone(ClockApp.zone(scope)).toInstant().toEpochMilli()
    }
}

/** The calendar provider's events, read and deleted by `_id` through `content` (design D9 of sub-project 6). */
object Calendars {
    private const val EVENTS = "content://com.android.calendar/events"

    /**
     * The device's own Google calendar: visible, `com.google`, with owner access (700), of the account whose address the
     * local configuration gives for the device, the owner's secondary account; null without one.
     */
    fun own(scope: RunScope): Long? {
        val address = scope.config.address(scope.device.kind) ?: return null
        val rows = Rows.parse(
            scope.device.read(
                "content query --uri content://com.android.calendar/calendars --projection _id:account_name:ownerAccount " +
                    "--where ${shellQuoted("account_type='com.google' AND calendar_access_level=700 AND visible=1")}",
            ),
        )
        return rows.firstOrNull { it["account_name"].equals(address, ignoreCase = true) && it["ownerAccount"].equals(address, ignoreCase = true) }?.get("_id")?.toLongOrNull()
    }

    /** The `_id`s of the events whose title is `LIKE` [title] (letter case aside; `%` for any text), and that start at [start] when given. */
    fun titled(scope: RunScope, title: String, start: Long? = null): List<Long> =
        ids(scope, "deleted=0 AND title LIKE '$title'" + (start?.let { " AND dtstart=$it" } ?: ""))

    /** The largest `_id` of the events, 0 without one; the provider never gives an `_id` twice, so a later event's is larger. */
    fun newest(scope: RunScope): Long = ids(scope, null).maxOrNull() ?: 0

    /**
     * The `_id`s of the events that came after [newest] gave [after], in any calendar into which an app may save
     * (contributor access or more, 500); with [title], only those titled [title] as a whole (letter case aside) that start
     * on the run's `{date}`.
     */
    fun added(scope: RunScope, after: Long, title: String? = null): List<Long> {
        val where = "_id>$after AND deleted=0 AND calendar_access_level>=$CONTRIBUTOR"
        if (title == null) return ids(scope, where)
        val (from, to) = day(scope)
        return ids(scope, "$where AND title LIKE '${title.replace("'", "''")}' AND dtstart>=$from AND dtstart<$to")
    }

    /**
     * Deletes the events that [find] gives, by their `_id`, until it gives none 3 s after a delete. Google Calendar's sync,
     * when it uploads the event while the delete runs, writes it back within a second (the A71, 2026-11-24), and a delete
     * after that holds. Fails when an event is still there after [DELETES] deletes.
     */
    fun deleteAll(scope: RunScope, find: () -> List<Long>) {
        repeat(DELETES) {
            val found = find()
            if (found.isEmpty()) return
            delete(scope, found)
            scope.clock.sleep(SETTLE)
        }
        check(find().isEmpty()) { "The test event is still in the calendar." }
    }

    fun delete(scope: RunScope, ids: List<Long>) {
        for (id in ids) scope.device.idempotent("content delete --uri $EVENTS --where ${shellQuoted("_id=$id")}")
    }

    private fun ids(scope: RunScope, where: String?): List<Long> =
        Rows.parse(scope.device.read("content query --uri $EVENTS --projection _id" + (where?.let { " --where ${shellQuoted(it)}" } ?: ""))).mapNotNull { it["_id"]?.toLongOrNull() }

    /** The run's `{date}` in the device's zone, from its first ms to the next day's first. */
    private fun day(scope: RunScope): Pair<Long, Long> {
        val date = scope.values.date ?: throw IllegalStateException("The run has no value for {date}.")
        val day = LocalDate.parse(date)
        return day.atStartOfDay(ClockApp.zone(scope)).toInstant().toEpochMilli() to day.plusDays(1).atStartOfDay(ClockApp.zone(scope)).toInstant().toEpochMilli()
    }

    private const val CONTRIBUTOR = 500
    private const val DELETES = 10
    private val SETTLE = 3.seconds
}

/** Tomorrow on the device, as YYYY-MM-DD in `{date}`; it changes nothing. */
data object Tomorrow : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.values.date = DeviceTime.now(scope).toLocalDate().plusDays(1).toString()
        return SetupResult.Ready
    }
}

/**
 * A test event on `{date}` from [start] to [end] in the device's own Google calendar ([Calendars.own]); [title] may hold
 * the run's placeholders. Its restore, pushed before the insert, deletes every event with that title by its `_id`
 * ([Calendars.deleteAll]), which finds the event also when the insert's answer was lost, and checks that none is left.
 * A device without that calendar cannot run the task.
 */
data class CalendarEvent(val title: String, val start: LocalTime, val end: LocalTime) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val calendar = Calendars.own(scope)
            ?: return SetupResult.Untestable("it has no visible Google calendar of the account that corpus\\owner\\a71-config.json names for it")
        val name = scope.values.fill(title)
        val byHand = "Calendar: delete the event \"$name\"."
        scope.restores.push("the test event", byHand) {
            Calendars.deleteAll(scope) { Calendars.titled(scope, name) }
        }
        scope.restores.change("the test event added", byHand) {
            // A lost answer is read back below.
            scope.device.once(
                "content insert --uri content://com.android.calendar/events --bind calendar_id:l:$calendar --bind title:s:${shellQuoted(name)} " +
                    "--bind dtstart:l:${DeviceTime.at(scope, start)} --bind dtend:l:${DeviceTime.at(scope, end)} --bind eventTimezone:s:${ClockApp.zone(scope).id}",
            )
        }
        if (Calendars.titled(scope, name).size != 1) throw SetupFailed("The test event did not reach the calendar.")
        return SetupResult.Ready
    }
}

/**
 * calendar-add's restore, pushed before the run (design D9 of sub-project 6). The run's own event is one that came after
 * the setup ([Calendars.newest]), in any calendar into which Kestrel may save, titled with the run's marker as a whole and
 * starting on `{date}`; every such event is deleted by its `_id` ([Calendars.deleteAll]) and checked. Any other event that
 * came during the run and is still there afterwards, such as one that Kestrel saved under another title or on another
 * day, is reported for a look by hand and never deleted, because a sync may have added it.
 */
data object CalendarCleanup : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val marker = scope.values.marker
        val after = Calendars.newest(scope)
        scope.restores.push("the test event", "Calendar: delete the event \"$marker\" of tomorrow.") {
            Calendars.deleteAll(scope) { Calendars.added(scope, after, marker) }
            val left = Calendars.added(scope, after).size
            if (left > 0) {
                scope.console.warn(
                    "Not put back: $left event(s) added during the run other than the run's event \"$marker\" of tomorrow. " +
                        "By hand: in Calendar, delete the event that Kestrel saved for \"$marker\", if it is one of them; a sync may have added the others.",
                )
            }
        }
        return SetupResult.Ready
    }
}

/** One event titled with the run's marker, whatever its letter case, starts on `{date}` at 15:00 on the device (design D8). */
data object EventAt : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean =
        Calendars.titled(scope, scope.values.marker, DeviceTime.at(scope, LocalTime.of(15, 0))).size == 1
}
