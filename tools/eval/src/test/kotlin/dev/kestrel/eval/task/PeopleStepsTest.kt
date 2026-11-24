package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** The calendar's and the contacts' steps and checks (design D4, D8 and D9 of sub-project 6). */
class PeopleStepsTest {
    private val none = HookAnswer.EMPTY
    private val config = LocalConfig.of(mapOf("emulatorGmailAddress" to "pera.second@example.com"))

    @Test
    fun rowsAreReadAsColumnsAndATextIsQuotedForTheShell() {
        assertEquals(listOf(mapOf("_id" to "3", "title" to "maple heron")), Rows.parse(listOf("Row: 0 _id=3, title=maple heron", "something else")))
        assertEquals(emptyList<Map<String, String>>(), Rows.parse(listOf("No result found.")))
        assertEquals("'title='\\''Ana'\\'''", shellQuoted("title='Ana'"))
        assertEquals("title='Ana'", FakeProviders.unshell(shellQuoted("title='Ana'")))
    }

    @Test
    fun theTestEventGoesIntoTheDevicesOwnGoogleCalendarTomorrowAndLeavesAgain() {
        val run = FakeRun(config = config)
        val providers = FakeProviders(run.adb)

        assertEquals(SetupResult.Ready, Tomorrow.apply(run.scope))
        assertEquals("2026-11-24", run.scope.values.date)
        assertEquals(SetupResult.Ready, CalendarEvent("Kestrel test {marker}", LocalTime.of(10, 0), LocalTime.of(11, 0)).apply(run.scope))
        assertEquals(listOf(FakeProviders.Event(1, 1, "Kestrel test maple heron", providers.at(24, 10))), providers.events)
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(providers.events.single().deleted)

        val unnamed = FakeRun()
        FakeProviders(unnamed.adb)
        Tomorrow.apply(unnamed.scope)
        assertTrue(CalendarEvent("Kestrel test {marker}", LocalTime.of(10, 0), LocalTime.of(11, 0)).apply(unnamed.scope) is SetupResult.Untestable)
        assertEquals(0, unnamed.restores.size)
    }

    @Test
    fun calendarAddsCheckFindsTheEventAtThreeTomorrowAndItsRestoreDeletesEveryEventWithTheMarker() {
        val run = FakeRun(config = config)
        val providers = FakeProviders(run.adb)
        providers.events.add(FakeProviders.Event(1, 1, "Dentist", providers.at(24, 15)))
        Tomorrow.apply(run.scope)
        CalendarCleanup.apply(run.scope)

        assertFalse(EventAt.passes(run.scope, none))
        providers.events.add(FakeProviders.Event(2, 1, "Maple Heron", providers.at(24, 14)))
        assertFalse(EventAt.passes(run.scope, none))
        providers.events.add(FakeProviders.Event(3, 1, "maple heron", providers.at(24, 15)))
        assertTrue(EventAt.passes(run.scope, none))
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(false, true, true), providers.events.map { it.deleted })
    }

    @Test
    fun contactAddsNumberIsFreeAndItsRestoreDeletesTheContactsThatKestrelAdded() {
        val run = FakeRun()
        val providers = FakeProviders(run.adb)
        providers.raws.add(FakeProviders.Raw(1, "Owner Friend", "+1 202 555 0160"))

        assertEquals(SetupResult.Ready, FreeNumber.apply(run.scope))
        assertEquals("+1 202 555 0161", run.scope.values.number)
        ContactCleanup.apply(run.scope)
        assertFalse(ContactSaved.passes(run.scope, none))
        providers.raws.add(FakeProviders.Raw(2, "Maple Heron", "+12025550161"))
        assertTrue(ContactSaved.passes(run.scope, none))
        providers.raws.add(FakeProviders.Raw(3, "maple heron", "(202) 555-0161"))
        assertFalse(ContactSaved.passes(run.scope, none))
        // A contact that Kestrel saved under another name leaves too, because no contact had the number before.
        providers.raws.add(FakeProviders.Raw(4, "Maple", "+1 202 555 0161"))
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(false, true, true, true), providers.raws.map { it.deleted })
    }

    @Test
    fun aDeviceContactIsAddedWithoutAnAccountRefusedWhenItsNameExistsAndCheckedUnchanged() {
        val run = FakeRun()
        val providers = FakeProviders(run.adb)
        providers.raws.add(FakeProviders.Raw(1, "Owner Friend", "+381 60 000 0000"))
        val ana = DeviceContact("Ana Test", "+1 202 555 0143")

        assertEquals(mapOf("contact:Ana Test" to "0"), ana.probe(run.scope))
        assertEquals(SetupResult.Ready, ana.apply(run.scope))
        assertEquals(FakeProviders.Raw(2, "Ana Test", "+1 202 555 0143"), providers.raws.last())
        assertTrue(run.adb.commands.any { it.matches(Regex("content insert --uri content://com\\.android\\.contacts/raw_contacts --bind starred:i:0 --bind sync1:s:kestrel-eval-\\S+")) })
        val unchanged = ContactUnchanged("Ana Test", "+1 202 555 0143")
        assertTrue(unchanged.passes(run.scope, none))
        providers.raws.last().number = "+1 202 555 0199"
        assertFalse(unchanged.passes(run.scope, none))
        providers.raws.last().number = "+1 202 555 0143"
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(providers.raws.last().deleted)
        assertFalse(providers.raws.first().deleted)

        providers.raws.add(FakeProviders.Raw(3, "Ana Test", "+1 202 555 0100"))
        assertThrows(SetupFailed::class.java) { ana.apply(run.scope) }
        assertEquals(0, run.restores.size)

        val lost = FakeRun()
        val other = FakeProviders(lost.adb)
        lost.adb.loseNext("raw_contacts --bind starred")
        assertEquals(SetupResult.Ready, ana.apply(lost.scope))
        assertEquals(listOf(FakeProviders.Raw(1, "Ana Test", "+1 202 555 0143")), other.raws)
        assertEquals(1, lost.adb.commands.count { it.startsWith("content insert --uri content://com.android.contacts/raw_contacts") })
    }

    @Test
    fun aContactThatASyncAddsDuringTheInsertIsNeverTakenForTheTestContact() {
        val run = FakeRun()
        val providers = FakeProviders(run.adb)
        providers.afterRawInsert = {
            providers.afterRawInsert = {}
            providers.raws.add(FakeProviders.Raw(providers.raws.size + 1L, "Owner Synced", "+381 60 111 2222"))
        }

        assertEquals(SetupResult.Ready, DeviceContact("Ana Test", "+1 202 555 0143").apply(run.scope))
        assertEquals(FakeProviders.Raw(1, "Ana Test", "+1 202 555 0143"), providers.raws[0])
        assertEquals("1", run.scope.notes[DeviceContact.RAW])
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(FakeProviders.Raw(1, "Ana Test", "+1 202 555 0143", deleted = true), FakeProviders.Raw(2, "Owner Synced", "+381 60 111 2222")), providers.raws)
    }

    @Test
    fun theTestContactLeavesAlsoWhenItsIdWasNeverReadBack() {
        val run = FakeRun()
        val providers = FakeProviders(run.adb)
        // The insert reaches the device, and then adb loses the answers of the lookup of its _id.
        providers.afterRawInsert = { providers.rawLookupFailures = 3 }

        assertThrows(Exception::class.java) { DeviceContact("Ana Test", "+1 202 555 0143").apply(run.scope) }
        assertEquals(null, run.scope.notes[DeviceContact.RAW])
        assertEquals(1, providers.raws.size)
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(providers.live().isEmpty())
    }

    @Test
    fun anEventThatKestrelSavedWithoutTheMarkerIsReportedAndNotDeleted() {
        val run = FakeRun(config = config)
        val providers = FakeProviders(run.adb)
        providers.events.add(FakeProviders.Event(1, 1, "Dentist", providers.at(24, 9)))
        Tomorrow.apply(run.scope)
        CalendarCleanup.apply(run.scope)
        providers.events.add(FakeProviders.Event(2, 1, "maple heron", providers.at(24, 15)))
        providers.events.add(FakeProviders.Event(3, 1, "Meeting", providers.at(24, 15)))
        // Events of another calendar or of another day are not the run's.
        providers.events.add(FakeProviders.Event(4, 2, "Holiday", providers.at(24, 0)))
        providers.events.add(FakeProviders.Event(5, 1, "Lunch", providers.at(25, 12)))

        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(false, true, false, false, false), providers.events.map { it.deleted })
        assertEquals(1, run.console.warnings.size)
        assertTrue(run.console.warnings.single(), run.console.warnings.single().contains("1 event"))

        val clean = FakeRun(config = config)
        FakeProviders(clean.adb).events.add(FakeProviders.Event(1, 1, "Dentist", providers.at(24, 9)))
        Tomorrow.apply(clean.scope)
        CalendarCleanup.apply(clean.scope)
        assertTrue(clean.restores.runAll().isEmpty())
        assertTrue(clean.console.warnings.isEmpty())
    }

    @Test
    fun aContactThatKestrelSavedWithAnotherNumberIsReportedAndNotDeleted() {
        val run = FakeRun()
        val providers = FakeProviders(run.adb)
        providers.raws.add(FakeProviders.Raw(1, "Owner Friend", "+381 60 000 0000"))
        FreeNumber.apply(run.scope)
        ContactCleanup.apply(run.scope)
        providers.raws.add(FakeProviders.Raw(2, "Maple Heron", "+1 202 555 0160"))
        providers.raws.add(FakeProviders.Raw(3, "Maple Heron", "+1 202 555 0106"))

        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(false, true, false), providers.raws.map { it.deleted })
        assertEquals(1, run.console.warnings.size)
        assertTrue(run.console.warnings.single(), run.console.warnings.single().contains("1 contact"))
    }
}
