package dev.kestrel.datatools.contacts

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.commands.phone.PhoneEntry

/**
 * A contact as the agent's `find_contact` sees it (design D12 of sub-project 5): the instant commands' [contact] with its
 * names and numbers, its e-mail addresses, and the messaging [apps] (names of [MessagingApps]) whose rows it has. Its text
 * form shows only counts (spec 13.5).
 */
data class ContactDetails(val contact: Contact, val emails: List<String>, val apps: Set<String>) {
    override fun toString() = "ContactDetails(${contact.names.size} names, ${contact.numbers.size} numbers, ${emails.size} addresses, ${apps.size} apps)"
}

/**
 * The rows by which messaging apps mark a contact that uses them in Android's contacts (research r53): Telegram's and
 * Signal's from their source code, WhatsApp's and Viber's from other apps' parsers. Viber Out's rows are paid calls to
 * ordinary numbers and prove nothing, so they are not here. A missing row proves nothing either.
 */
object MessagingApps {
    const val TELEGRAM = "Telegram"
    const val WHATSAPP = "WhatsApp"
    const val VIBER = "Viber"
    const val SIGNAL = "Signal"

    /** Each app's row by its mimetype, in the order in which a result names the apps. */
    val MIMETYPES: Map<String, String> = linkedMapOf(
        "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile" to TELEGRAM,
        "vnd.android.cursor.item/vnd.com.whatsapp.profile" to WHATSAPP,
        "vnd.android.cursor.item/vnd.com.viber.voip.viber_number_message" to VIBER,
        "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact" to SIGNAL,
    )

    fun appOf(mimetype: String?): String? = MIMETYPES[mimetype]

    /** [apps] in the order of [MIMETYPES]. */
    fun ordered(apps: Set<String>): List<String> = MIMETYPES.values.filter { it in apps }
}

/** One row of Android's `ContactsContract.Data` as [AndroidContacts] reads it. It is never logged. */
internal class ContactRow(
    val contactId: Long,
    val displayName: String?,
    val mimetype: String?,
    val value: String?,
    val type: Int,
    val label: String?,
    val starred: Boolean,
    val superPrimary: Boolean,
)

/** Turns the provider's rows into contacts (design D12 of sub-project 5). Pure, for the JVM tests. */
internal object ContactRows {
    private class Building {
        val names = mutableListOf<String>()
        val numbers = mutableListOf<PhoneEntry>()
        val emails = mutableListOf<String>()
        val apps = mutableSetOf<String>()
        var starred = false
    }

    /**
     * One contact per contact ID, in the order of the rows: the display name first, then the nicknames, the phone numbers
     * with their kinds, the e-mail addresses and the apps whose rows it has. A contact without a name is left out.
     */
    fun fold(rows: List<ContactRow>): List<ContactDetails> {
        val built = LinkedHashMap<Long, Building>()
        for (row in rows) {
            val contact = built.getOrPut(row.contactId) { Building() }
            row.displayName?.let { if (it !in contact.names) contact.names.add(0, it) }
            contact.starred = row.starred
            MessagingApps.appOf(row.mimetype)?.let { contact.apps += it }
            val value = row.value ?: continue
            when (row.mimetype) {
                Nickname.CONTENT_ITEM_TYPE -> if (value !in contact.names) contact.names += value
                Phone.CONTENT_ITEM_TYPE -> contact.numbers += PhoneEntry(value, kind(row.type, row.label), row.superPrimary)
                Email.CONTENT_ITEM_TYPE -> if (value !in contact.emails) contact.emails += value
            }
        }
        return built.filterValues { it.names.isNotEmpty() }.map { (id, contact) ->
            ContactDetails(Contact(id, contact.names.toList(), contact.numbers.toList(), contact.starred), contact.emails.toList(), contact.apps.toSet())
        }
    }

    /**
     * Telegram writes its row on a raw contact of its own, without a number, which Android joins to the owner's contact by
     * name and may fail to join, for example for a Serbian name (research r53). So a contact that has only apps' rows joins
     * the contact with a number or an address that `ContactMatcher` finds as the only one for its name, and its apps go
     * with it; when its name finds none or several, it stays as it is.
     */
    fun joinAppOnly(contacts: List<ContactDetails>): List<ContactDetails> {
        val (appOnly, full) = contacts.partition { it.contact.numbers.isEmpty() && it.emails.isEmpty() && it.apps.isNotEmpty() }
        val merged = LinkedHashMap(full.associateBy { it.contact.id })
        val kept = mutableListOf<ContactDetails>()
        for (only in appOnly) {
            val match = ContactMatcher.find(only.contact.names.first(), full.map { it.contact })
            val target = (match as? ContactMatch.One)?.let { merged[it.contact.id] }
            if (target == null) kept += only else merged[target.contact.id] = target.copy(apps = target.apps + only.apps)
        }
        return contacts.mapNotNull { contact -> merged[contact.contact.id] ?: contact.takeIf { it in kept } }
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
}
