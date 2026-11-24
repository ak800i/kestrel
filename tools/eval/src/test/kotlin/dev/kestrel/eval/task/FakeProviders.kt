package dev.kestrel.eval.task

import dev.kestrel.eval.device.FakeAdb
import java.net.URLDecoder
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The calendar and contacts providers of a device for the tests, as `content` answers them: the device's own Google
 * calendar (1), a read-only holiday calendar (2) of [address] and a local calendar (3), events, and raw contacts with one
 * name and one number each, whose contact `_id` is their own, and their `sync1`. Phone lookup compares the last 10
 * digits, as Android compares numbers loosely. The device's time is noon of 2026-11-23 in Belgrade. [afterEventDelete]
 * runs after an event's delete, as a sync that writes it back, [afterRawInsert] runs after a raw contact's insert, as a
 * sync that adds one in the same second, and [rawLookupFailures] fails that many queries of the raw contacts.
 */
class FakeProviders(adb: FakeAdb, address: String = "pera.second@example.com") {
    data class Event(val id: Long, val calendar: Long, val title: String, val start: Long, var deleted: Boolean = false)

    data class Raw(val id: Long, var name: String? = null, var number: String? = null, var deleted: Boolean = false) {
        var sync1: String? = null
    }

    val events = mutableListOf<Event>()
    val raws = mutableListOf<Raw>()
    var afterEventDelete: (Event) -> Unit = {}
    var afterRawInsert: () -> Unit = {}
    var rawLookupFailures = 0
    private val zone = ZoneId.of("Europe/Belgrade")

    fun at(day: Int, hour: Int): Long = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    fun live(): List<Raw> = raws.filter { !it.deleted }

    init {
        adb.props["persist.sys.timezone"] = "Europe/Belgrade"
        adb.on("^date \\+%s$") { "${at(23, 12) / 1000}" }
        adb.on("^content query --uri content://com\\.android\\.calendar/calendars ") {
            "Row: 0 _id=1, account_name=$address, ownerAccount=$address\nRow: 1 _id=2, account_name=$address, ownerAccount=holidays@group.v.calendar.google.com"
        }
        adb.on("^content insert --uri content://com\\.android\\.calendar/events --bind calendar_id:l:(\\d+) --bind title:s:(.+) --bind dtstart:l:(\\d+) --bind dtend") {
            events.add(Event((events.maxOfOrNull { event -> event.id } ?: 0) + 1, it.groupValues[1].toLong(), unshell(it.groupValues[2]), it.groupValues[3].toLong()))
            ""
        }
        adb.on("^content query --uri content://com\\.android\\.calendar/events --projection _id( --where (.+))?$") { match ->
            val terms = unshell(match.groupValues[2]).split(" AND ").filter { it.isNotEmpty() }
            rows(events.filter { event -> terms.all { holds(event, it) } }.map { mapOf("_id" to "${it.id}") })
        }
        adb.on("^content delete --uri content://com\\.android\\.calendar/events --where '_id=(\\d+)'$") { match ->
            events.filter { it.id == match.groupValues[1].toLong() }.forEach {
                it.deleted = true
                afterEventDelete(it)
            }
            ""
        }
        adb.on("^content query --uri content://com\\.android\\.contacts/raw_contacts --projection _id( --where (.+))?$") { match ->
            if (rawLookupFailures > 0) {
                rawLookupFailures--
                return@on null
            }
            val where = unshell(match.groupValues[2])
            val found = when {
                where.isEmpty() -> raws
                WHERE_RAW_ID.matches(where) -> live().filter { it.id == WHERE_RAW_ID.matchEntire(where)!!.groupValues[2].toLong() }
                WHERE_RAW_ABOVE.matches(where) -> live().filter { it.id > WHERE_RAW_ABOVE.matchEntire(where)!!.groupValues[1].toLong() }
                else -> live().filter { it.sync1 == WHERE_RAW_SYNC1.matchEntire(where)!!.groupValues[1] }
            }
            rows(found.map { mapOf("_id" to "${it.id}") })
        }
        adb.on("^content insert --uri content://com\\.android\\.contacts/raw_contacts --bind starred:i:0( --bind sync1:s:(\\S+))?$") { match ->
            raws.add(Raw(raws.size + 1L).apply { sync1 = match.groupValues[2].ifEmpty { null } })
            afterRawInsert()
            ""
        }
        adb.on("^content insert --uri content://com\\.android\\.contacts/data --bind raw_contact_id:l:(\\d+) --bind mimetype:s:vnd\\.android\\.cursor\\.item/(name|phone_v2) --bind data1:s:('[^']*')") { match ->
            val raw = raws.first { it.id == match.groupValues[1].toLong() }
            if (match.groupValues[2] == "name") raw.name = unshell(match.groupValues[3]) else raw.number = unshell(match.groupValues[3])
            ""
        }
        adb.on("^content query --uri content://com\\.android\\.contacts/phone_lookup/(\\S+) --projection _id:display_name$") { match ->
            val digits = URLDecoder.decode(match.groupValues[1], Charsets.UTF_8).filter { it.isDigit() }.takeLast(10)
            rows(live().filter { it.number?.filter { char -> char.isDigit() }?.takeLast(10) == digits }.map { mapOf("_id" to "${it.id}", "display_name" to it.name.orEmpty()) })
        }
        adb.on("^content query --uri content://com\\.android\\.contacts/contacts --projection _id --where (.+)$") { match ->
            val name = unshell(match.groupValues[1]).removePrefix("display_name='").removeSuffix("'")
            rows(live().filter { it.name == name }.map { mapOf("_id" to "${it.id}") })
        }
        adb.on("^content delete --uri content://com\\.android\\.contacts/raw_contacts --where '_id=(\\d+)'$") { match ->
            raws.filter { it.id == match.groupValues[1].toLong() }.forEach { it.deleted = true }
            ""
        }
    }

    companion object {
        private val ACCESS = mapOf(1L to 700, 2L to 200, 3L to 700)
        private val TERM = Regex("(_id|calendar_id|calendar_access_level|deleted|dtstart)(=|>=|>|<)(\\d+)|title LIKE '([^']*)'")
        private val WHERE_RAW_ID = Regex("(contact_id|_id)=(\\d+) AND deleted=0")
        private val WHERE_RAW_ABOVE = Regex("_id>(\\d+) AND deleted=0")
        private val WHERE_RAW_SYNC1 = Regex("sync1='([^']*)' AND deleted=0")

        /** Whether [event] meets one term of an events query's `--where`, which joins its terms with AND. */
        private fun holds(event: Event, term: String): Boolean {
            val match = TERM.matchEntire(term) ?: throw IllegalArgumentException("The fake calendar cannot read the term '$term'.")
            if (match.groupValues[1].isEmpty()) return like(event.title, match.groupValues[4])
            val value = when (match.groupValues[1]) {
                "_id" -> event.id
                "calendar_id" -> event.calendar
                "calendar_access_level" -> ACCESS.getValue(event.calendar).toLong()
                "deleted" -> if (event.deleted) 1L else 0L
                else -> event.start
            }
            val bound = match.groupValues[3].toLong()
            return when (match.groupValues[2]) {
                "=" -> value == bound
                ">=" -> value >= bound
                ">" -> value > bound
                else -> value < bound
            }
        }

        /** SQL's `LIKE` for the tests' patterns: a whole title, or with `%` at both ends a part of one, whatever the letter case. */
        private fun like(title: String, pattern: String): Boolean =
            if (pattern.length > 1 && pattern.startsWith("%") && pattern.endsWith("%")) title.contains(pattern.trim('%'), ignoreCase = true) else title.equals(pattern, ignoreCase = true)

        /** `content query`'s answer: "Row: <n> <name>=<value>, …", or "No result found." without rows. */
        fun rows(rows: List<Map<String, String>>): String =
            if (rows.isEmpty()) "No result found." else rows.mapIndexed { index, row -> "Row: $index " + row.entries.joinToString(", ") { "${it.key}=${it.value}" } }.joinToString("\n")

        /** A shell word with its single quotes undone, and `'\''` as one quote. */
        fun unshell(word: String): String {
            val text = StringBuilder()
            var quoted = false
            var at = 0
            while (at < word.length) {
                val char = word[at]
                when {
                    char == '\'' -> quoted = !quoted
                    char == '\\' && !quoted && at + 1 < word.length -> text.append(word[++at])
                    else -> text.append(char)
                }
                at++
            }
            return text.toString()
        }
    }
}
