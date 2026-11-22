package dev.kestrel.datatools.contacts

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import dev.kestrel.commands.phone.Contact

/** Where the contacts come from: Android's contacts provider, or a fake in the JVM tests. */
interface ContactsSource {
    /** Every contact with its names and numbers. Throws [SecurityException] without `READ_CONTACTS`. */
    fun read(): List<Contact>

    /** Calls [changed] after each change of the contacts until the returned handle closes. Throws without the permission. */
    fun watch(changed: () -> Unit): AutoCloseable
}

/**
 * The owner's contacts for "call [name]" (design spec, section 13.6; research r20), kept in memory. Nothing touches the
 * provider until the first read that finds `READ_CONTACTS` granted; that read registers the watch for changes only after
 * it has read, because Android refuses the registration without the permission. After a change, the next read reads
 * again. When the permission is missing at a read, the reader drops what it kept and its watch, so that a later grant
 * starts afresh. It never logs a name or a number; a failure is reported by its classes only.
 */
class ContactsReader(
    private val access: ContactsAccess,
    private val source: ContactsSource,
    private val report: (String, Throwable) -> Unit,
) {
    private var kept: List<Contact>? = null
    private var watch: AutoCloseable? = null

    @Volatile
    private var changed = false

    /** The contacts, or null without the permission. */
    @Synchronized
    fun contacts(): List<Contact>? {
        if (!access.allowed()) {
            drop()
            return null
        }
        // Kept only while a watch tells of changes; after a refused watch, every read reads again.
        kept?.let { if (!changed && watch != null) return it }
        // Cleared before the read, so that a change during the read leads to another read next time.
        changed = false
        val contacts = try {
            source.read()
        } catch (e: SecurityException) {
            drop()
            return null
        }
        kept = contacts
        if (watch == null) {
            watch = try {
                source.watch { changed = true }
            } catch (e: SecurityException) {
                report("Watching the contacts", e)
                null
            }
        }
        return contacts
    }

    private fun drop() {
        kept = null
        changed = false
        watch?.close()
        watch = null
    }
}

/**
 * The contacts of Android's provider, read by mimetype in one query, never with SQL `LIKE` (research r53): for the instant
 * commands names, nicknames and phone numbers; for the agent's `find_contact` also the e-mail addresses and the messaging
 * apps' rows (design D12 of sub-project 5). Call it off the main thread.
 */
class AndroidContacts(private val resolver: ContentResolver) : ContactsSource {
    override fun read(): List<Contact> = ContactRows.fold(rows(BASIC)).map { it.contact }

    /** Every contact with its e-mail addresses and the messaging apps whose rows it has. Throws without `READ_CONTACTS`. */
    fun details(): List<ContactDetails> = ContactRows.fold(rows(BASIC + Email.CONTENT_ITEM_TYPE + MessagingApps.MIMETYPES.keys))

    override fun watch(changed: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = changed()
        }
        resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        return AutoCloseable { resolver.unregisterContentObserver(observer) }
    }

    private fun rows(kinds: List<String>): List<ContactRow> {
        val rows = mutableListOf<ContactRow>()
        val selection = "${Data.MIMETYPE} IN (${kinds.joinToString(", ") { "?" }})"
        resolver.query(Data.CONTENT_URI, COLUMNS, selection, kinds.toTypedArray(), Data._ID)?.use { cursor ->
            while (cursor.moveToNext()) {
                val mimetype = cursor.getString(2)
                rows += ContactRow(
                    contactId = cursor.getLong(0),
                    displayName = cursor.getString(1),
                    mimetype = mimetype,
                    value = cursor.getString(3),
                    // Only a phone row keeps a number in DATA2; the apps' rows keep text there.
                    type = if (mimetype == Phone.CONTENT_ITEM_TYPE) cursor.getInt(4) else 0,
                    label = if (mimetype == Phone.CONTENT_ITEM_TYPE) cursor.getString(5) else null,
                    starred = cursor.getInt(6) != 0,
                    superPrimary = cursor.getInt(7) != 0,
                )
            }
        }
        return rows
    }

    private companion object {
        val BASIC = listOf(StructuredName.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE)

        // The display name of the contact, the row's kind and value, a phone number's type and label, the star and
        // whether the number is the contact's default.
        val COLUMNS = arrayOf(
            Data.CONTACT_ID, Data.DISPLAY_NAME, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.STARRED, Data.IS_SUPER_PRIMARY,
        )
    }
}
