package dev.kestrel.datatools.sms

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.datatools.DataLines
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * `list_sms` and `compose_sms` (design D12 of sub-project 5): the owner's received and sent SMS, newest first, at most
 * [MAX], optionally from one number or one contact, with the contact's name while [contacts] gives the contacts (null
 * without `READ_CONTACTS`); and the SMS app's compose screen with a prefilled text, whose send happens on the screen.
 * Kestrel never asks for `SEND_SMS` (spec 13.6).
 */
class SmsTool(
    private val resolver: ContentResolver,
    private val granted: (String) -> Boolean,
    private val contacts: () -> List<Contact>?,
) {
    suspend fun list(from: String?, limit: Int, zone: ZoneId): DataResult {
        if (!granted(Manifest.permission.READ_SMS)) return DataResult.NeedsPermission(listOf(Manifest.permission.READ_SMS))
        val count = limit.coerceIn(1, MAX)
        val wanted = from?.trim()?.takeIf { it.isNotEmpty() }
        val lines = withContext(Dispatchers.IO) {
            val known = contacts()
            val numbers = wanted?.let { numbersOf(it, known) }
            val found = mutableListOf<DataLine>()
            val selection = "${Telephony.Sms.TYPE} IN (${Telephony.Sms.MESSAGE_TYPE_INBOX}, ${Telephony.Sms.MESSAGE_TYPE_SENT})"
            resolver.query(Telephony.Sms.CONTENT_URI, COLUMNS, selection, null, "${Telephony.Sms.DATE} DESC")?.use { rows ->
                while (found.size < count && rows.moveToNext()) {
                    val address = rows.getString(0)
                    if (wanted != null && !matches(wanted, numbers, address)) continue
                    val name = known?.let { nameOf(address, it) }
                    found += DataLines.sms(rows.getInt(2) == Telephony.Sms.MESSAGE_TYPE_INBOX, address, name, rows.getString(3), rows.getLong(1), zone)
                }
            }
            found
        }
        return DataResult.Ok(lines)
    }

    companion object {
        const val MAX = 20

        private val COLUMNS = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.BODY)

        /** The SMS app's compose screen for [number] with [text], through `smsto:`. */
        fun composeIntent(number: String, text: String): Intent =
            Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
                .putExtra("sms_body", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /**
         * The numbers of the contacts that [wanted] names, when it is a name and Kestrel has the [contacts]; null when it is
         * a number or there are no contacts to look in.
         */
        fun numbersOf(wanted: String, contacts: List<Contact>?): List<String>? {
            if (wanted.count(Char::isDigit) >= 3 || contacts == null) return null
            val found = when (val match = ContactMatcher.find(wanted, contacts)) {
                is ContactMatch.One -> listOf(match.contact)
                is ContactMatch.Several -> match.contacts
                else -> emptyList()
            }
            return found.flatMap { contact -> contact.numbers.map { it.number } }
        }

        /** Whether an SMS with [address] is from or to [wanted]: its number, its contact's [numbers], or a sender's name. */
        fun matches(wanted: String, numbers: List<String>?, address: String?): Boolean = when {
            wanted.count(Char::isDigit) >= 3 -> DataLines.sameNumber(wanted, address)
            numbers?.any { DataLines.sameNumber(it, address) } == true -> true
            else -> address != null && Normalizer.normalize(address) == Normalizer.normalize(wanted)
        }

        /** The display name of the contact with [address]'s number, if any. */
        fun nameOf(address: String?, contacts: List<Contact>): String? =
            contacts.firstOrNull { contact -> contact.numbers.any { DataLines.sameNumber(it.number, address) } }?.names?.firstOrNull()
    }
}
