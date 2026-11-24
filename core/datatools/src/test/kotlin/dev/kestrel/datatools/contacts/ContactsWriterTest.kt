package dev.kestrel.datatools.contacts

import dev.kestrel.agent.port.DataResult
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** add_contact adds nothing for a contact that already has the number, and find_contact's lines carry the contacts' IDs (design D19 of sub-project 6). */
class ContactsWriterTest {
    private val ana = Contact(41, listOf("Ana Test"), listOf(PhoneEntry("+1 202 555 0143", "mobile", superPrimary = false)), starred = false)

    @Test
    fun aContactOfTheSameNameAndTheSameNumberIsADuplicate() {
        assertTrue(ContactsWriter.duplicate("Ana Test", "+12025550143", listOf(ana)))
        assertTrue(ContactsWriter.duplicate("ana test", "(202) 555-0143", listOf(ana)))
        assertFalse(ContactsWriter.duplicate("Ana Test", "+1 202 555 0199", listOf(ana)))
        assertFalse(ContactsWriter.duplicate("Marko", "+1 202 555 0143", listOf(ana)))
    }

    @Test
    fun findContactsLinesCarryTheContactsIdsAsTheirHandles() {
        val found = FindContact.result("Ana Test", listOf(ContactDetails(ana, emptyList(), emptySet()))) as DataResult.Ok

        assertEquals(listOf("41"), found.lines.map { it.ref })
    }
}
