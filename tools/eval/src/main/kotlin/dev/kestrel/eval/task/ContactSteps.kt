package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer
import java.net.URLEncoder

/**
 * The contacts provider through `content` (design D9 of sub-project 6): Android's phone lookup, which compares numbers
 * whatever their format, and the raw contacts, deleted by their `_id`. It reads only the `_id`s and the names of the
 * contacts that have a test number.
 */
object Contacts {
    private const val RAW = "content://com.android.contacts/raw_contacts"

    /** The contacts that have [number]: their contact `_id` and display name. */
    fun withNumber(scope: RunScope, number: String): List<Map<String, String>> {
        val digits = URLEncoder.encode("+" + number.filter { it.isDigit() }, Charsets.UTF_8)
        return Rows.parse(scope.device.read("content query --uri content://com.android.contacts/phone_lookup/$digits --projection _id:display_name"))
    }

    /** How many contacts are named exactly [name]. */
    fun named(scope: RunScope, name: String): Int =
        Rows.parse(scope.device.read("content query --uri content://com.android.contacts/contacts --projection _id --where ${shellQuoted("display_name='$name'")}")).size

    /** The largest raw contact `_id`, or 0 without one. */
    fun newestRaw(scope: RunScope): Long = Dumps.largestRowId(scope.device.read("content query --uri $RAW --projection _id"))

    fun rawExists(scope: RunScope, id: Long): Boolean =
        Rows.parse(scope.device.read("content query --uri $RAW --projection _id --where ${shellQuoted("_id=$id AND deleted=0")}")).size == 1

    /** Deletes every contact named [name], whatever its letter case, that has [number], with all its raw contacts. */
    fun remove(scope: RunScope, name: String, number: String) {
        for (contact in withNumber(scope, number).filter { it["display_name"].equals(name, ignoreCase = true) }) {
            val raws = Rows.parse(scope.device.read("content query --uri $RAW --projection _id --where ${shellQuoted("contact_id=${contact["_id"]} AND deleted=0")}"))
            raws.mapNotNull { it["_id"]?.toLongOrNull() }.forEach { deleteRaw(scope, it) }
        }
    }

    /** Deletes every contact that has [number], whatever its name, with all its raw contacts. */
    fun removeNumber(scope: RunScope, number: String) {
        for (contact in withNumber(scope, number)) {
            val raws = Rows.parse(scope.device.read("content query --uri $RAW --projection _id --where ${shellQuoted("contact_id=${contact["_id"]} AND deleted=0")}"))
            raws.mapNotNull { it["_id"]?.toLongOrNull() }.forEach { deleteRaw(scope, it) }
        }
    }

    fun deleteRaw(scope: RunScope, id: Long) {
        scope.device.idempotent("content delete --uri $RAW --where ${shellQuoted("_id=$id")}")
    }
}

/** contact-add's number `{number}`: +1 202 555 01nn, with the first nn from 60 to 69 that no contact of the device has (design D8). */
data object FreeNumber : Setup {
    val NUMBERS = (60..69).map { "+1 202 555 01$it" }

    override fun apply(scope: RunScope): SetupResult {
        scope.values.number = NUMBERS.firstOrNull { Contacts.withNumber(scope, it).isEmpty() } ?: return SetupResult.Untestable("every test number belongs to a contact")
        return SetupResult.Ready
    }
}

/**
 * contact-add's restore, pushed before the run (design D9 of sub-project 6): every contact that has `{number}` is
 * deleted with its raw contacts, whatever name Kestrel gave it, because [FreeNumber] chose a number that no contact had
 * before the run; and checked.
 */
data object ContactCleanup : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val number = scope.values.number ?: throw IllegalStateException("The run has no value for {number}.")
        scope.restores.push("the test contact", "Contacts: delete the contact with the number $number.") {
            Contacts.removeNumber(scope, number)
            check(Contacts.withNumber(scope, number).isEmpty()) { "A contact with the test number is still on the device." }
        }
        return SetupResult.Ready
    }
}

/** Exactly one contact named with the run's marker, whatever its letter case, has `{number}` (design D8). */
data object ContactSaved : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val number = scope.values.number ?: return false
        return Contacts.withNumber(scope, number).count { it["display_name"].equals(scope.values.marker, ignoreCase = true) } == 1
    }
}

/**
 * A test contact [name] with the mobile number [number] in the phone's own contacts, without an account, so that it
 * never syncs (design D4 of sub-project 6); both may hold the run's placeholders. The run fails when a contact with that
 * name exists already, so that its restore deletes only the contact that it made: the raw contact that it recorded, and
 * any contact found again by its name and number, also when an insert's answer was lost.
 */
data class DeviceContact(val name: String, val number: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val shown = scope.values.fill(name)
        val phone = scope.values.fill(number)
        if (Contacts.named(scope, shown) > 0) throw SetupFailed("A contact named $shown exists already, so the run does not touch it.")
        val before = Contacts.newestRaw(scope)
        val byHand = "Contacts: delete the contact $shown."
        scope.restores.push("the test contact $shown", byHand) {
            scope.notes[RAW]?.toLongOrNull()?.let { Contacts.deleteRaw(scope, it) }
            Contacts.remove(scope, shown, phone)
            check(Contacts.named(scope, shown) == 0) { "The test contact $shown is still on the device." }
        }
        val raw = scope.restores.change("the test contact $shown added", byHand) {
            // A lost answer is read back by the newest raw contact.
            scope.device.once("content insert --uri content://com.android.contacts/raw_contacts --bind starred:i:0")
            Contacts.newestRaw(scope).takeIf { it > before } ?: throw SetupFailed("The test contact did not reach the device.")
        }
        scope.notes[RAW] = "$raw"
        scope.restores.change("the test contact's name and number added", byHand) {
            scope.device.once("content insert --uri content://com.android.contacts/data --bind raw_contact_id:l:$raw --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:${shellQuoted(shown)}")
            scope.device.once(
                "content insert --uri content://com.android.contacts/data --bind raw_contact_id:l:$raw --bind mimetype:s:vnd.android.cursor.item/phone_v2 " +
                    "--bind data1:s:${shellQuoted(phone)} --bind data2:i:2",
            )
        }
        if (Contacts.withNumber(scope, phone).none { it["display_name"] == shown }) throw SetupFailed("The test contact $shown did not reach the device.")
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("contact:${scope.values.fill(name)}" to "${Contacts.named(scope, scope.values.fill(name))}")

    companion object {
        /** The key of [RunScope.notes] that holds the test contact's raw contact `_id`. */
        const val RAW = "test-raw-contact"
    }
}

/** The test contact of [DeviceContact] is still there, with its name and its number (design D8: contact-delete-declined). */
data class ContactUnchanged(val name: String, val number: String) : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val raw = scope.notes[DeviceContact.RAW]?.toLongOrNull() ?: return false
        return Contacts.rawExists(scope, raw) && Contacts.withNumber(scope, number).any { it["display_name"] == name }
    }
}
