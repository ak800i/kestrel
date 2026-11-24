package dev.kestrel.datatools.contacts

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.datatools.DataLines

/**
 * The agent's `add_contact` and `delete_contact` (design D19 of sub-project 6). [add] writes one raw contact without an
 * account, the phone's own contacts, which never sync to an online account, with its display name and one mobile number,
 * in one batch; [delete] deletes an aggregate contact with all its raw contacts, and a raw contact of an online account is
 * deleted there at its next sync. Call it off the main thread; it throws [SecurityException] without `WRITE_CONTACTS`.
 * It never logs a name or a number.
 */
class ContactsWriter(private val resolver: ContentResolver) {
    /** The new raw contact's ID, or null when Android added none. */
    fun add(name: String, number: String): Long? {
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_TYPE, null)
                .withValue(RawContacts.ACCOUNT_NAME, null)
                .build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE)
                .withValue(StructuredName.DISPLAY_NAME, name)
                .build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE)
                .withValue(Phone.NUMBER, number)
                .withValue(Phone.TYPE, Phone.TYPE_MOBILE)
                .build(),
        )
        return resolver.applyBatch(ContactsContract.AUTHORITY, operations).firstOrNull()?.uri?.let(ContentUris::parseId)
    }

    /** Whether Android deleted the aggregate contact [contactId]. */
    fun delete(contactId: Long): Boolean = resolver.delete(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), null, null) > 0

    companion object {
        /** Whether [contacts] hold a contact that the contact matcher finds for [name] and that has [number] (the same phone). */
        fun duplicate(name: String, number: String, contacts: List<Contact>): Boolean {
            val named = when (val match = ContactMatcher.find(name, contacts)) {
                is ContactMatch.One -> listOf(match.contact)
                is ContactMatch.Several -> match.contacts
                else -> emptyList()
            }
            return named.any { contact -> contact.numbers.any { DataLines.sameNumber(it.number, number) } }
        }
    }
}
