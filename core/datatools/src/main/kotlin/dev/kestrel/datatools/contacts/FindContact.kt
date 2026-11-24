package dev.kestrel.datatools.contacts

import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher

/**
 * The agent's `find_contact` (design D12 of sub-project 5; research r53): the contacts that `ContactMatcher` finds for
 * [name] (folded Serbian names, Ana and Anna, Latin and Cyrillic), at most [ContactMatcher.MAX_CHOICES], each with its
 * names, numbers, e-mail addresses and messaging apps. Memory's aliases are resolved before (plan 5A's `DataSteps`). The
 * fields are untrusted and unquoted; `:core:agent` redacts and quotes them. The field `name` is the contact's display
 * name, which `DataSteps` remembers as a found contact (D32 item 44).
 */
object FindContact {
    const val NO_APP = "no app link found; the contact may still use an app"

    fun result(name: String, contacts: List<ContactDetails>): DataResult {
        val joined = ContactRows.joinAppOnly(contacts)
        val byId = joined.associateBy { it.contact.id }
        val found = when (val match = ContactMatcher.find(name, joined.map { it.contact })) {
            is ContactMatch.One -> listOf(match.contact)
            is ContactMatch.Several -> match.contacts
            is ContactMatch.TooMany -> return DataResult.Ok(emptyList(), note = "${match.count} contacts match; ask for a fuller name.")
            ContactMatch.None -> emptyList()
        }
        return DataResult.Ok(found.mapNotNull { byId[it.id] }.mapIndexed(::line))
    }

    private fun line(index: Int, details: ContactDetails): DataLine {
        val apps = MessagingApps.ordered(details.apps)
        val fixed = "contact ${index + 1} (${if (apps.isEmpty()) NO_APP else "apps: ${apps.joinToString(", ")}"})"
        val names = details.contact.names
        val fields = listOf(DataField("name", names.first())) +
            names.drop(1).map { DataField("other name", it) } +
            details.contact.numbers.map { DataField("phone", "${it.number} (${it.kind})") } +
            details.emails.map { DataField("e-mail", it) }
        // The contact's ID stays on the phone as the line's handle, which delete_contact uses (design D19 of sub-project 6).
        return DataLine(fixed, fields, ref = details.contact.id.toString())
    }
}
