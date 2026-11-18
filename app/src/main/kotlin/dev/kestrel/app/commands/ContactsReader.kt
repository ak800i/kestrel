package dev.kestrel.app.commands

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Data
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry

/** Where the contacts come from: Android's contacts provider, or a fake in the JVM tests. */
internal interface ContactsSource {
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
internal class ContactsReader(
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

/** The contacts of Android's provider: names, nicknames and phone numbers, never other data. */
internal class AndroidContacts(private val resolver: ContentResolver) : ContactsSource {
    override fun read(): List<Contact> {
        val names = LinkedHashMap<Long, MutableList<String>>()
        val numbers = HashMap<Long, MutableList<PhoneEntry>>()
        val starred = HashMap<Long, Boolean>()
        val kinds = arrayOf(StructuredName.CONTENT_ITEM_TYPE, Nickname.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE)
        resolver.query(Data.CONTENT_URI, COLUMNS, "${Data.MIMETYPE} IN (?, ?, ?)", kinds, Data._ID)?.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.getLong(0)
                val contactNames = names.getOrPut(id) { mutableListOf() }
                rows.getString(1)?.let { if (it !in contactNames) contactNames.add(0, it) }
                starred[id] = rows.getInt(6) != 0
                val value = rows.getString(3) ?: continue
                when (rows.getString(2)) {
                    Nickname.CONTENT_ITEM_TYPE -> if (value !in contactNames) contactNames += value
                    Phone.CONTENT_ITEM_TYPE -> numbers.getOrPut(id) { mutableListOf() } +=
                        PhoneEntry(value, kind(rows.getInt(4), rows.getString(5)), rows.getInt(7) != 0)
                }
            }
        }
        return names.filterValues { it.isNotEmpty() }.map { (id, contactNames) ->
            Contact(id, contactNames, numbers[id].orEmpty(), starred[id] == true)
        }
    }

    override fun watch(changed: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = changed()
        }
        resolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
        return AutoCloseable { resolver.unregisterContentObserver(observer) }
    }

    private fun kind(type: Int, label: String?): String = when (type) {
        Phone.TYPE_MOBILE -> "mobile"
        Phone.TYPE_WORK -> "work"
        Phone.TYPE_HOME -> "home"
        Phone.TYPE_MAIN -> "main"
        Phone.TYPE_WORK_MOBILE -> "work mobile"
        Phone.TYPE_CUSTOM -> label?.takeIf { it.isNotBlank() } ?: "other"
        else -> "other"
    }

    private companion object {
        // The display name of the contact, the row's kind and value, a phone number's type and label, the star and
        // whether the number is the contact's default.
        val COLUMNS = arrayOf(
            Data.CONTACT_ID, Data.DISPLAY_NAME, Data.MIMETYPE, Data.DATA1, Data.DATA2, Data.DATA3, Data.STARRED, Data.IS_SUPER_PRIMARY,
        )
    }
}
