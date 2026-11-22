package dev.kestrel.datatools

import android.provider.CallLog
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry
import dev.kestrel.datatools.clock.ClockIntents
import dev.kestrel.datatools.sms.SmsTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Calendar

class DataLinesTest {
    private val belgrade = ZoneId.of("Europe/Belgrade")

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
    fun anSmsFromAContactIsNamedByTheContactWhoseNumberEndsTheSame() {
        val ana = Contact(1, listOf("Ana Petrović"), listOf(PhoneEntry("064 123 4567", "mobile", superPrimary = false)), starred = false)

        assertEquals("Ana Petrović", SmsTool.nameOf("+381641234567", listOf(ana)))
        assertNull(SmsTool.nameOf("+381641234568", listOf(ana)))
        assertTrue(DataLines.sameNumber("+1 202 555 0143", "2025550143"))
        assertFalse(DataLines.sameNumber("1234", "51234"))
        // By name only with the contacts; without them a name compares with an alphanumeric sender.
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
