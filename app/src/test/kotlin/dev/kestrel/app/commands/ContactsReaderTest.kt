package dev.kestrel.app.commands

import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsReaderTest {
    private val ana = Contact(1, listOf("Ana Petrović"), listOf(PhoneEntry("064 123 4567", "mobile", superPrimary = false)), starred = false)

    /** The provider as the tests see it: what it was asked, in order. */
    private class Provider(var contacts: List<Contact>) : ContactsSource {
        val calls = mutableListOf<String>()
        var refuseWatch = false
        var changed: (() -> Unit)? = null

        override fun read(): List<Contact> {
            calls += "read"
            return contacts
        }

        override fun watch(changed: () -> Unit): AutoCloseable {
            calls += "watch"
            if (refuseWatch) throw SecurityException("Permission Denial: Ana Petrović")
            this.changed = changed
            return AutoCloseable { calls += "unwatch" }
        }
    }

    private var allowed = true
    private val reports = mutableListOf<String>()
    private val provider = Provider(listOf(ana))
    private val reader = ContactsReader({ allowed }, provider) { what, error -> reports += "$what: ${error.javaClass.simpleName}" }

    @Test
    fun nothingIsReadOrWatchedBeforeTheFirstReadWithThePermission() {
        allowed = false

        assertNull(reader.contacts())
        assertEquals(emptyList<String>(), provider.calls)

        allowed = true
        assertEquals(listOf(ana), reader.contacts())
        // The watch comes after the read, because Android refuses it without the permission.
        assertEquals(listOf("read", "watch"), provider.calls)
    }

    @Test
    fun theContactsAreKeptUntilTheyChange() {
        reader.contacts()
        reader.contacts()
        assertEquals(listOf("read", "watch"), provider.calls)

        val marko = ana.copy(id = 2, names = listOf("Marko"))
        provider.contacts = listOf(ana, marko)
        checkNotNull(provider.changed).invoke()

        assertEquals(listOf(ana, marko), reader.contacts())
        assertEquals(listOf("read", "watch", "read"), provider.calls)
    }

    @Test
    fun aLostPermissionDropsTheContactsAndTheWatchAndAGrantStartsAfresh() {
        reader.contacts()
        allowed = false

        assertNull(reader.contacts())
        assertEquals(listOf("read", "watch", "unwatch"), provider.calls)

        allowed = true
        reader.contacts()
        assertEquals(listOf("read", "watch", "unwatch", "read", "watch"), provider.calls)
    }

    @Test
    fun aRefusedWatchIsReportedByItsClassAndTheContactsStillCome() {
        provider.refuseWatch = true

        assertEquals(listOf(ana), reader.contacts())
        assertEquals(listOf("Watching the contacts: SecurityException"), reports)
        // Without a watch, nothing tells the reader of a change, so it reads again and tries the watch again.
        assertEquals(listOf(ana), reader.contacts())
        assertEquals(listOf("read", "watch", "read", "watch"), provider.calls)
    }
}
