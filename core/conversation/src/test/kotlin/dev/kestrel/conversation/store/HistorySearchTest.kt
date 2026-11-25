package dev.kestrel.conversation.store

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.HistoryData
import dev.kestrel.agent.goal.HistoryMatch
import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.conversation.testing.MemoryArchive
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HistorySearchTest {
    private val clock = ManualConversationClock()
    private val day = ArchivedConversationStore.DAY_MILLIS
    private val now = clock.wallMillis()
    private val store = ArchivedConversationStore(MemoryArchive()) {}
    private var retention = 30
    private val search = HistorySearch(store, { retention }, clock)

    private fun id(n: Int) = ConversationId(n.toString().padStart(32, '0'))

    private fun record(number: Int, request: String, age: Long, answer: String? = null, data: List<DataNote> = emptyList()) =
        CommandRecord(number, now - age, InputChannel.TEXT, request, answer = answer, end = EndReason.FINISHED, data = data, apps = listOf(AppUse("Viber", "com.viber.voip")))

    private suspend fun save(n: Int, vararg records: CommandRecord, open: Boolean = false) {
        val last = records.maxOf { it.atWall }
        store.save(ConversationSnapshot(id(n), records.minOf { it.atWall }, last, open, testMode = true, records = records.toList()))
    }

    private suspend fun requests(text: String, from: LocalDate? = null, to: LocalDate? = null, excluding: RecordRef? = null): List<String> =
        search.search(HistoryQuery(text, from, to), excluding).matches.map { it.request }

    @Test
    fun foldingMatchesCaseDiacriticsAndBothScripts() = runTest {
        save(1, record(1, "Pronađi restoran koji je Mila poslala", day))

        assertEquals(listOf("Pronađi restoran koji je Mila poslala"), requests("PRONADJI restoran"))
        assertEquals(1, requests("Мила").size)
    }

    @Test
    fun aNameMatchesByANearMiss() = runTest {
        save(1, record(1, "Send the photo to Milicu", day))

        assertEquals(1, requests("Milica").size)
        assertTrue(requests("Marko").isEmpty())
    }

    @Test
    fun aRecordMustHoldHalfOfTheQueryWords() = runTest {
        save(1, record(1, "Find the restaurant Mila sent", day))

        assertEquals(1, requests("restaurant Mila Belgrade").size)
        assertTrue(requests("restaurant Belgrade Novi Sad").isEmpty())
        assertTrue(requests("the about").isEmpty())
    }

    @Test
    fun moreWordsRankHigherAndTiesGoToTheNewer() = runTest {
        save(1, record(1, "restaurant", 3 * day), record(2, "restaurant Mila", 3 * day - 1))
        save(2, record(1, "restaurant", day))

        assertEquals(listOf("restaurant Mila", "restaurant", "restaurant"), requests("restaurant Mila"))
        assertEquals(now - day, search.search(HistoryQuery("restaurant Mila")).matches[1].atMillis)
    }

    @Test
    fun theDateRangeAndRetentionBoundTheSearch() = runTest {
        save(1, record(1, "Ambar dinner", 2 * day))
        save(2, record(1, "Ambar lunch", 40 * day))
        val today = LocalDate.of(2026, 11, 20)

        assertEquals(listOf("Ambar dinner"), requests("Ambar"))
        assertEquals(listOf("Ambar dinner"), requests("Ambar", from = today.minusDays(3), to = today.minusDays(2)))
        assertTrue(requests("Ambar", from = today.minusDays(1)).isEmpty())
        retention = 365
        assertEquals(2, requests("Ambar").size)
    }

    @Test
    fun aResultHoldsAtMostFiveMatchesAndSaysThatMoreMatched() = runTest {
        save(1, *(1..7).map { record(it, "Ambar $it", day - it) }.toTypedArray())

        val result = search.search(HistoryQuery("Ambar"))

        assertEquals(5, result.matches.size)
        assertTrue(result.more)
        assertEquals(listOf("Ambar 7", "Ambar 6", "Ambar 5", "Ambar 4", "Ambar 3"), result.matches.map { it.request })

        save(2, record(1, "Kafana Ambar", day - 10, "It's Ambar.", listOf(DataNote("list_sms", 1, "body: \"Ambar\"", items = 1))))
        assertEquals(
            HistoryMatch(now - day + 10, 1, "Kafana Ambar", "It's Ambar.", listOf("Viber"), listOf(HistoryData("list_sms", "body: \"Ambar\""))),
            search.search(HistoryQuery("Kafana")).matches.single(),
        )
        assertFalse(search.search(HistoryQuery("Kafana")).more)
    }

    @Test
    fun theOpenConversationIsSearchedExceptTheAskingCommand() = runTest {
        save(1, record(1, "Ambar dinner", 60_000), record(2, "Ambar again", 0), open = true)

        assertEquals(listOf("Ambar dinner"), requests("Ambar", excluding = RecordRef(id(1), 2)))
        assertEquals(listOf("Ambar dinner"), search.port(RecordRef(id(1), 2)).search(HistoryQuery("Ambar")).matches.map { it.request })
    }

    @Test
    fun theIndexFollowsDeletesAReopenAndForgetAll() = runTest {
        save(1, record(1, "Ambar dinner", day))
        save(2, record(1, "Ambar lunch", 2 * day))
        assertEquals(2, requests("Ambar").size)
        assertEquals(2, search.indexed)

        store.delete(id(2))
        assertEquals(listOf("Ambar dinner"), requests("Ambar"))
        assertEquals(1, search.indexed)

        store.reopen(id(1), null, now, testMode = true)
        assertEquals(listOf("Ambar dinner"), requests("Ambar"))

        search.forgetAll()
        assertEquals(0, search.indexed)
    }

    @Test
    fun aPastConversationIsFoundByItsTopicOrIsTheNewestWithoutOne() = runTest {
        save(1, record(1, "Plan the trip to Novi Sad", 3 * day), record(2, "Book the hotel", 3 * day - 1))
        save(2, record(1, "Read my latest SMS", day))
        save(3, record(1, "Turn on Wi-Fi", 0), open = true)

        assertEquals(ConversationMatch(id(1), now - 3 * day, "Plan the trip to Novi Sad"), search.conversation("the hotel"))
        assertEquals(id(2), search.conversation(null)!!.id)
        assertNull(search.conversation("Wi-Fi"))
        assertFalse(search.conversation("trip")!!.firstRequest.isEmpty())
    }

    @Test
    fun theSearchsTypesPrintNoWords() = runTest {
        val marker = "Zorana"
        val record = record(1, "Call $marker", day, answer = marker)
        save(1, record)
        assertEquals(1, requests(marker).size)
        val entry = SearchEntry.of(id(1), record)
        val values = listOf(entry, RankedEntry(entry, 1), search.conversation(marker)!!, search, search.port(RecordRef(id(1), 1)))

        for (value in values) assertFalse(value.javaClass.simpleName, value.toString().contains(marker, ignoreCase = true))
    }
}
