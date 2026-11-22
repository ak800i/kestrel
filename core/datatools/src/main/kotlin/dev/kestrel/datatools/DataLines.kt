package dev.kestrel.datatools

import android.provider.CallLog
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The result lines of the data tools that read Android's providers (design D12 of sub-project 5): Kestrel's own words,
 * such as kinds and times, in the fixed text; every text that comes from the owner's data (titles, places, names,
 * numbers, bodies) as an unquoted field, which `:core:agent` redacts and quotes. A blank text gives no field. Pure, so
 * that the JVM tests check it.
 */
object DataLines {
    /** Two phone numbers with at least this many digits are the same when their last this many digits are. */
    const val SAME_DIGITS = 8

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

    fun time(millis: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(millis).atZone(zone))

    /**
     * "event · 2026-11-20 12:00 to 13:30", or "event · 2026-11-19 · all day" for an event that Android stores in UTC, and
     * "event · 2026-11-19 to 2026-11-21 · all day" for one that covers several days.
     */
    fun event(title: String?, begin: Long, end: Long, allDay: Boolean, location: String?, calendar: String?, zone: ZoneId): DataLine {
        val fixed = if (allDay) {
            val days = allDayDates(begin, end)
            val shown = if (days.endInclusive == days.start) DAY.format(days.start) else "${DAY.format(days.start)} to ${DAY.format(days.endInclusive)}"
            "event · $shown · all day"
        } else {
            val from = Instant.ofEpochMilli(begin).atZone(zone)
            val to = Instant.ofEpochMilli(end).atZone(zone)
            "event · ${TIME.format(from)} to ${if (to.toLocalDate() == from.toLocalDate()) CLOCK.format(to) else TIME.format(to)}"
        }
        return DataLine(fixed, listOfNotNull(field("title", title), field("location", location), field("calendar", calendar)))
    }

    /**
     * The days of an all-day event, which Android stores from the UTC midnight of its first day to the UTC midnight after
     * its last one.
     */
    fun allDayDates(begin: Long, end: Long): ClosedRange<LocalDate> {
        val first = utcDate(begin)
        return first..maxOf(first, utcDate(end - 1))
    }

    private fun utcDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    /** "sms · received · 2026-11-20 10:20", with the other side by the contact's [name] and its number, and the body. */
    fun sms(received: Boolean, address: String?, name: String?, body: String?, date: Long, zone: ZoneId): DataLine {
        val fixed = "sms · ${if (received) "received" else "sent"} · ${time(date, zone)}"
        val side = if (received) "from" else "to"
        return DataLine(fixed, listOfNotNull(field(side, name ?: address), field("number", address.takeIf { name != null }), field("text", body)))
    }

    /** "call · missed · 2026-11-20 10:20 · 0 s", with the name that the call log keeps and the number. */
    fun call(type: Int, name: String?, number: String?, date: Long, durationSeconds: Long, zone: ZoneId): DataLine =
        DataLine("call · ${callKind(type)} · ${time(date, zone)} · ${duration(durationSeconds)}", listOfNotNull(field("name", name), field("number", number)))

    fun callKind(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        else -> "other"
    }

    fun duration(seconds: Long): String = if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else "$seconds s"

    /**
     * Whether two phone numbers name the same phone: their last [SAME_DIGITS] digits when both have that many, so that a
     * country code or a leading zero does not count, else all their digits.
     */
    fun sameNumber(a: String?, b: String?): Boolean {
        val first = a?.filter(Char::isDigit).orEmpty()
        val second = b?.filter(Char::isDigit).orEmpty()
        if (first.isEmpty() || second.isEmpty()) return false
        return if (first.length >= SAME_DIGITS && second.length >= SAME_DIGITS) first.takeLast(SAME_DIGITS) == second.takeLast(SAME_DIGITS) else first == second
    }

    private fun field(name: String, value: String?): DataField? = value?.takeIf { it.isNotBlank() }?.let { DataField(name, it) }
}
