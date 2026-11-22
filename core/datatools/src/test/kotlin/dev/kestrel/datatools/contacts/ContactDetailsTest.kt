package dev.kestrel.datatools.contacts

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import dev.kestrel.agent.port.DataResult
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDetailsTest {
    private val telegram = "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile"
    private val whatsApp = "vnd.android.cursor.item/vnd.com.whatsapp.profile"
    private val viberOut = "vnd.android.cursor.item/vnd.com.viber.voip.viber_out_call_viber"

    private fun row(id: Long, name: String, mimetype: String, value: String?, type: Int = 0, starred: Boolean = false) =
        ContactRow(id, name, mimetype, value, type, label = null, starred = starred, superPrimary = false)

    private fun contact(id: Long, name: String, number: String? = null, email: String? = null, apps: Set<String> = emptySet()) = ContactDetails(
        Contact(id, listOf(name), listOfNotNull(number?.let { PhoneEntry(it, "mobile", superPrimary = false) }), starred = false),
        listOfNotNull(email),
        apps,
    )

    @Test
    fun rowsBecomeContactsWithTheirAddressesAndApps() {
        val details = ContactRows.fold(
            listOf(
                row(1, "Ana Petrović", StructuredName.CONTENT_ITEM_TYPE, "Ana Petrović"),
                row(1, "Ana Petrović", Nickname.CONTENT_ITEM_TYPE, "Anči"),
                row(1, "Ana Petrović", Phone.CONTENT_ITEM_TYPE, "+1 202 555 0143", Phone.TYPE_MOBILE),
                row(1, "Ana Petrović", Email.CONTENT_ITEM_TYPE, "ana@example.com"),
                row(1, "Ana Petrović", telegram, null),
                row(1, "Ana Petrović", viberOut, "x"),
                row(2, "Marko", StructuredName.CONTENT_ITEM_TYPE, "Marko", starred = true),
            ),
        )

        assertEquals(listOf("Ana Petrović", "Anči"), details[0].contact.names)
        assertEquals(listOf(PhoneEntry("+1 202 555 0143", "mobile", superPrimary = false)), details[0].contact.numbers)
        assertEquals(listOf("ana@example.com"), details[0].emails)
        // A row whose value is empty still links the app; Viber Out's rows are paid calls and prove nothing (r53).
        assertEquals(setOf(MessagingApps.TELEGRAM), details[0].apps)
        assertTrue(details[1].contact.starred)
        assertEquals(emptySet<String>(), details[1].apps)
    }

    @Test
    fun aTelegramRowOnAContactOfItsOwnJoinsTheContactThatItsNameFinds() {
        val ana = contact(1, "Ana Petrović", number = "+1 202 555 0143")
        val telegramOnly = contact(2, "Ana Petrovic", apps = setOf(MessagingApps.TELEGRAM))
        val marko = contact(3, "Marko", number = "+1 202 555 0144")
        val stranger = contact(4, "Jelena", apps = setOf(MessagingApps.WHATSAPP))

        val joined = ContactRows.joinAppOnly(listOf(ana, telegramOnly, marko, stranger))

        assertEquals(listOf(1L, 3L, 4L), joined.map { it.contact.id })
        assertEquals(setOf(MessagingApps.TELEGRAM), joined[0].apps)
        // A contact with only an app's row whose name finds no other contact stays as it is.
        assertEquals(setOf(MessagingApps.WHATSAPP), joined[2].apps)
    }

    @Test
    fun findContactGivesNamesNumbersAddressesAndAppsAndSaysWhenNoAppLinkWasFound() {
        val ana = contact(1, "Ana Petrović", number = "+1 202 555 0143", email = "ana@example.com", apps = setOf(MessagingApps.VIBER, MessagingApps.TELEGRAM))
        val marko = contact(2, "Marko Marković", number = "+1 202 555 0144")

        val found = FindContact.result("Ana Petrović", listOf(ana, marko)) as DataResult.Ok
        val line = found.lines.single()
        assertEquals("contact 1 (apps: Telegram, Viber)", line.fixed)
        assertEquals(listOf("name" to "Ana Petrović", "phone" to "+1 202 555 0143 (mobile)", "e-mail" to "ana@example.com"), line.fields.map { it.name to it.value })

        val plain = FindContact.result("Marko", listOf(ana, marko)) as DataResult.Ok
        assertEquals("contact 1 (${FindContact.NO_APP})", plain.lines.single().fixed)
        assertEquals(emptyList<Any>(), (FindContact.result("Zoran", listOf(ana, marko)) as DataResult.Ok).lines)
    }

    @Test
    fun findContactGivesUpToFourContactsAndOnlyACountForMore() {
        val anas = (1..4).map { contact(it.toLong(), "Ana Test$it", number = "+1 202 555 014$it") }

        val four = FindContact.result("Ana", anas) as DataResult.Ok
        assertEquals(listOf("contact 1", "contact 2", "contact 3", "contact 4"), four.lines.map { it.fixed.substringBefore(" (") })

        val five = FindContact.result("Ana", anas + contact(5, "Ana Test5", number = "+1 202 555 0145")) as DataResult.Ok
        assertEquals(emptyList<Any>(), five.lines)
        assertEquals("5 contacts match; ask for a fuller name.", five.note)
    }

    @Test
    fun theTextOfContactDetailsHoldsOnlyCounts() {
        val text = contact(1, "Ana Petrović", number = "+1 202 555 0143", email = "ana@example.com", apps = setOf(MessagingApps.TELEGRAM)).toString()

        assertFalse("Ana" in text || "555" in text || "example" in text || "Telegram" in text)
    }
}
