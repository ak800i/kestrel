package dev.kestrel.datatools

import android.Manifest
import android.provider.CallLog
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry
import dev.kestrel.datatools.calendar.CalendarTool
import dev.kestrel.datatools.clock.ClockIntents
import dev.kestrel.datatools.sms.SmsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Calendar

class DataLinesTest {
    private val belgrade = ZoneId.of("Europe/Belgrade")
    private val newYork = ZoneId.of("America/New_York")

    private fun millis(text: String) = LocalDateTime.parse(text).atZone(belgrade).toInstant().toEpochMilli()

    @Test
    fun anEventLineHasKestrelsTimesAndItsTextsAsFields() {
        val line = DataLines.event("Lunch", millis("2026-11-20T12:00"), millis("2026-11-20T13:30"), false, "Main Street 1", "Personal", belgrade)

        assertEquals("event · 2026-11-20 12:00 to 13:30", line.fixed)
        assertEquals(listOf("title" to "Lunch", "location" to "Main Street 1", "calendar" to "Personal"), line.fields.map { it.name to it.value })
        // An all-day event is stored at midnight UTC, so its day is read in UTC; an empty location gives no field.
        val allDay = DataLines.event("Holiday", 1_795_046_400_000L, 1_795_132_800_000L, true, "", "Personal", belgrade)
        assertEquals("event · 2026-11-19 · all day", allDay.fixed)
        assertEquals(listOf("title", "calendar"), allDay.fields.map { it.name })
    }

    @Test
    fun anEventIsListedOnlyOnTheDaysThatItCovers() {
        val day = LocalDate.of(2026, 11, 20)
        val utcMidnight = { date: String -> LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val covers = { begin: Long, end: Long, allDay: Boolean -> CalendarTool.covers(begin, end, allDay, day, day, belgrade) }

        // An all-day event is compared by its UTC days, so yesterday's and tomorrow's are not listed.
        assertFalse(covers(utcMidnight("2026-11-19"), utcMidnight("2026-11-20"), true))
        assertTrue(covers(utcMidnight("2026-11-20"), utcMidnight("2026-11-21"), true))
        assertTrue(covers(utcMidnight("2026-11-19"), utcMidnight("2026-11-22"), true))
        assertFalse(covers(utcMidnight("2026-11-21"), utcMidnight("2026-11-22"), true))
        assertFalse(CalendarTool.covers(utcMidnight("2026-11-21"), utcMidnight("2026-11-22"), true, day, day, newYork))
        // A timed event that ends as the range starts, or starts as it ends, is not listed; one without a length is.
        assertFalse(covers(millis("2026-11-19T22:00"), millis("2026-11-20T00:00"), false))
        assertTrue(covers(millis("2026-11-19T23:00"), millis("2026-11-20T01:00"), false))
        assertFalse(covers(millis("2026-11-21T00:00"), millis("2026-11-21T01:00"), false))
        assertTrue(covers(millis("2026-11-20T00:00"), millis("2026-11-20T00:00"), false))

        // An all-day event shows every day that it covers, read in UTC also west of it.
        assertEquals("event · 2026-11-19 to 2026-11-21 · all day", DataLines.event("Trip", utcMidnight("2026-11-19"), utcMidnight("2026-11-22"), true, null, null, newYork).fixed)
        assertEquals("event · 2026-11-19 · all day", DataLines.event("Holiday", utcMidnight("2026-11-19"), utcMidnight("2026-11-20"), true, null, null, newYork).fixed)
    }

    @Test
    fun listEventsSaysHowManyEventsMatchedWhenItListsOnlyTheFirst20() {
        val lines = (1..25).map { DataLine("event $it", emptyList()) }

        val result = CalendarTool.result(lines)

        assertEquals(lines.take(20) + DataLine("Kestrel lists the first 20 of 25 events.", emptyList()), result.lines)
        assertEquals(lines.take(20), CalendarTool.result(lines.take(20)).lines)
    }

    @Test
    fun listSmsByANameThatKestrelCannotLookUpSaysWhy() {
        val ana = { id: Long, name: String -> Contact(id, listOf(name), listOf(PhoneEntry("064 123 456$id", "mobile", superPrimary = false)), starred = false) }

        assertEquals(DataResult.NeedsPermission(listOf(Manifest.permission.READ_CONTACTS)), SmsTool.refusal("Ana", null))
        val many = (1L..5L).map { ana(it, "Ana Test$it") }
        val tooMany = SmsTool.refusal("Ana", many) as DataResult.Failed
        assertTrue(tooMany.reason.contains("find_contact"))
        assertFalse(tooMany.reason.contains("Ana"))
        // Four contacts, a name that no contact has, and a number are looked up.
        assertNull(SmsTool.refusal("Ana", many.take(4)))
        assertNull(SmsTool.refusal("Telenor", many))
        assertNull(SmsTool.refusal("064 123 4561", null))
    }

    @Test
    fun anSmsFromAContactIsNamedByTheContactWhoseNumberEndsTheSame() {
        val ana = Contact(1, listOf("Ana Petrović"), listOf(PhoneEntry("064 123 4567", "mobile", superPrimary = false)), starred = false)

        assertEquals("Ana Petrović", SmsTool.nameOf("+381641234567", listOf(ana)))
        assertNull(SmsTool.nameOf("+381641234568", listOf(ana)))
        assertTrue(DataLines.sameNumber("+1 202 555 0143", "2025550143"))
        assertFalse(DataLines.sameNumber("1234", "51234"))
        // Without the contacts, list_sms refuses a name (SmsTool.refusal); matches alone compares it with an alphanumeric sender.
        assertEquals(listOf("064 123 4567"), SmsTool.numbersOf("Ana", listOf(ana)))
        assertTrue(SmsTool.matches("Ana", listOf("064 123 4567"), "+381641234567"))
        assertNull(SmsTool.numbersOf("Telenor", null))
        assertTrue(SmsTool.matches("telenor", null, "Telenor"))
        // With the contacts too, a name that is no contact's compares with an alphanumeric sender.
        assertEquals(emptyList<String>(), SmsTool.numbersOf("Telenor", listOf(ana)))
        assertTrue(SmsTool.matches("Telenor", emptyList(), "Telenor"))
        assertTrue(SmsTool.matches("064 123 4567", null, "+381641234567"))

        val line = DataLines.sms(received = true, address = "+381641234567", name = "Ana Petrović", body = "Kasnim 10 minuta", date = millis("2026-11-20T10:20"), zone = belgrade)
        assertEquals("sms · received · 2026-11-20 10:20", line.fixed)
        assertEquals(listOf("from" to "Ana Petrović", "number" to "+381641234567", "text" to "Kasnim 10 minuta"), line.fields.map { it.name to it.value })
    }

    @Test
    fun aCallLineSaysItsKindTimeAndDuration() {
        val line = DataLines.call(CallLog.Calls.MISSED_TYPE, "Ana Petrović", "+381641234567", millis("2026-11-20T10:20"), 0, belgrade)

        assertEquals("call · missed · 2026-11-20 10:20 · 0 s", line.fixed)
        assertEquals("3 min 12 s", DataLines.duration(192))
        assertEquals("outgoing", DataLines.callKind(CallLog.Calls.OUTGOING_TYPE))
        assertEquals(listOf("number"), DataLines.call(CallLog.Calls.INCOMING_TYPE, null, "+381641234567", 0, 5, belgrade).fields.map { it.name })
    }

    @Test
    fun anAlarmsDaysAreTheCalendarsDayNumbers() {
        assertEquals(arrayListOf(Calendar.MONDAY, Calendar.FRIDAY, Calendar.SUNDAY), ClockIntents.calendarDays(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY)))
    }
}
