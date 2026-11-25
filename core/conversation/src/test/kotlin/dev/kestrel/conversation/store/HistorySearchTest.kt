package dev.kestrel.conversation.store

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.goal.HistoryData
import dev.kestrel.agent.goal.HistoryMatch
import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.goal.HistoryResult
import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.conversation.testing.MemoryArchive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

class HistorySearchTest {
    private val clock = ManualConversationClock()
    private val day = ArchivedConversationStore.DAY_MILLIS
    private val now = clock.wallMillis()
    private val archive = MemoryArchive()
    private var retention = 30
    private lateinit var store: ArchivedConversationStore
    private lateinit var search: HistorySearch

    // The store has read the empty archive, so that its changes write the index at once.
    private fun test(body: suspend TestScope.() -> Unit) = runTest {
        store = ArchivedConversationStore(archive, backgroundScope) {}
        search = HistorySearch(store, { retention }, clock)
        store.list()
        body()
    }

    private fun id(n: Int) = ConversationId(n.toString().padStart(32, '0'))

    private fun record(number: Int, request: String, age: Long, answer: String? = null, data: List<DataNote> = emptyList()) =
        CommandRecord(number, now - age, InputChannel.TEXT, request, answer = answer, end = EndReason.FINISHED, data = data, apps = listOf(AppUse("Viber", "com.viber.voip")))

    private suspend fun save(n: Int, vararg records: CommandRecord, open: Boolean = false) {
        val last = records.maxOf { it.atWall }
        store.save(ConversationSnapshot(id(n), records.minOf { it.atWall }, last, open, testMode = true, records = records.toList()))
    }

    private suspend fun requests(text: String, from: LocalDate? = null, to: LocalDate? = null, excluding: RecordRef? = null): List<String> =
        search.search(HistoryQuery(text, from, to), excluding).matches.map { it.request }

    private suspend fun found(topic: String?): ConversationMatch? = (search.conversation(topic) as? ConversationLookup.Found)?.match

    @Test
    fun foldingMatchesCaseDiacriticsAndBothScripts() = test {
        save(1, record(1, "Pronađi restoran koji je Mila poslala", day))

        assertEquals(listOf("Pronađi restoran koji je Mila poslala"), requests("PRONADJI restoran"))
        assertEquals(1, requests("Мила").size)
    }

    @Test
    fun aNameMatchesByANearMiss() = test {
        save(1, record(1, "Send the photo to Milicu", day))

        assertEquals(1, requests("Milica").size)
        assertTrue(requests("Marko").isEmpty())
    }

    @Test
    fun aRecordMustHoldHalfOfTheQueryWords() = test {
        save(1, record(1, "Find the restaurant Mila sent", day))

        assertEquals(1, requests("restaurant Mila Belgrade").size)
        assertTrue(requests("restaurant Belgrade Novi Sad").isEmpty())
        assertTrue(requests("the about").isEmpty())
    }

    @Test
    fun moreWordsRankHigherAndTiesGoToTheNewer() = test {
        save(1, record(1, "restaurant", 3 * day), record(2, "restaurant Mila", 3 * day - 1))
        save(2, record(1, "restaurant", day))

        assertEquals(listOf("restaurant Mila", "restaurant", "restaurant"), requests("restaurant Mila"))
        assertEquals(now - day, search.search(HistoryQuery("restaurant Mila")).matches[1].atMillis)
    }

    @Test
    fun theDateRangeAndRetentionBoundTheSearch() = test {
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
    fun aResultHoldsAtMostFiveMatchesAndSaysThatMoreMatched() = test {
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
    fun theOpenConversationIsSearchedExceptTheAskingCommand() = test {
        save(1, record(1, "Ambar dinner", 60_000), record(2, "Ambar again", 0), open = true)

        assertEquals(listOf("Ambar dinner"), requests("Ambar", excluding = RecordRef(id(1), 2)))
        assertEquals(listOf("Ambar dinner"), search.port(RecordRef(id(1), 2)).search(HistoryQuery("Ambar")).matches.map { it.request })
    }

    @Test
    fun theSearchFollowsDeletesAndAReopen() = test {
        save(1, record(1, "Ambar dinner", day))
        save(2, record(1, "Ambar lunch", 2 * day))
        assertEquals(2, requests("Ambar").size)

        store.delete(id(2))
        assertEquals(listOf("Ambar dinner"), requests("Ambar"))

        store.reopen(id(1), null, now, testMode = true)
        assertEquals(listOf("Ambar dinner"), requests("Ambar"))

        store.deleteAll()
        assertTrue(requests("Ambar").isEmpty())
    }

    @Test
    fun aPastConversationIsFoundByItsTopicOrIsTheNewestWithoutOne() = test {
        save(1, record(1, "Plan the trip to Novi Sad", 3 * day), record(2, "Book the hotel", 3 * day - 1))
        save(2, record(1, "Read my latest SMS", day))
        save(3, record(1, "Turn on Wi-Fi", 0), open = true)

        assertEquals(ConversationMatch(id(1), now - 3 * day, "Plan the trip to Novi Sad"), found("the hotel"))
        assertEquals(id(2), found(null)!!.id)
        assertEquals(ConversationLookup.NotFound, search.conversation("Wi-Fi"))
        assertFalse(found("trip")!!.firstRequest.isEmpty())
    }

    // Design 9.2 and 9.4 of sub-project 7: a rebuild of the index takes seconds on the A71, and the search never waits for
    // it; nor does it give the open conversation's matches alone, which would read as "nothing older matched".
    @Test
    fun whileTheIndexIsRebuiltOrCannotBeReadTheSearchSaysSo() = test {
        save(1, record(1, "Ambar dinner", day))
        save(2, record(1, "Ambar lunch", 0), open = true)
        archive.shards.clear()
        val gate = CompletableDeferred<Unit>()
        archive.closedIdsGate = gate
        store = ArchivedConversationStore(archive, backgroundScope) {}
        search = HistorySearch(store, { retention }, clock)

        assertEquals(HistoryResult.REBUILDING, search.search(HistoryQuery("Ambar")))
        assertEquals(ConversationLookup.Rebuilding, search.conversation("Ambar"))

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("Ambar lunch", "Ambar dinner"), requests("Ambar"))

        archive.failures["readShards"] = IOException("the disk failed")
        store = ArchivedConversationStore(archive, backgroundScope) {}
        search = HistorySearch(store, { retention }, clock)

        assertEquals(HistoryResult.FAILED, search.search(HistoryQuery("Ambar")))
        assertEquals(ConversationLookup.Unavailable, search.conversation("Ambar"))
    }

    @Test
    fun aSearchReadsOnlyTheBodiesOfItsMatchesInOneCall() = test {
        save(1, record(1, "Ambar dinner", 3 * day))
        save(2, record(1, "Kafana lunch", 2 * day))
        save(3, record(1, "Ambar again", day))
        store = ArchivedConversationStore(archive, backgroundScope) {}
        search = HistorySearch(store, { retention }, clock)
        archive.calls.clear()

        assertEquals(listOf("Ambar again", "Ambar dinner"), requests("Ambar"))

        assertEquals(listOf("readOpen", "readShards", "readStamp", "read"), archive.calls)
        assertTrue(requests("Wi-Fi").isEmpty())
        assertEquals(listOf("readOpen", "readShards", "readStamp", "read"), archive.calls)
    }

    // Design 9.7 of sub-project 7: "delete history" that runs while a search reads gives the search nothing to show. The
    // matches are in the open conversation, which the search reads from memory, and the lookup reads no body, so only the
    // store's generation stops them.
    @Test
    fun aSearchThatDeleteHistoryOvertookGivesNothingOut() = test {
        val racing = object : ConversationStore by store {
            var onRead: (suspend () -> Unit)? = null

            override suspend fun searchable(): Searchable {
                val read = store.searchable()
                onRead?.let { onRead = null; it() }
                return read
            }
        }
        val search = HistorySearch(racing, { retention }, clock)
        save(1, record(1, "Ambar dinner", day), open = true)
        assertEquals(1, search.search(HistoryQuery("Ambar")).matches.size)

        racing.onRead = { store.deleteAll() }
        assertTrue(search.search(HistoryQuery("Ambar")).matches.isEmpty())

        save(2, record(1, "Ambar lunch", day))
        racing.onRead = { store.deleteAll() }
        assertEquals(ConversationLookup.NotFound, search.conversation("Ambar"))
    }

    @Test
    fun theSearchsTypesPrintNoWords() = test {
        val marker = "Zorana"
        val record = record(1, "Call $marker", day, answer = marker)
        save(1, record)
        assertEquals(1, requests(marker).size)
        val index = SearchIndex.of(listOf(ConversationSnapshot(id(1), 0, 0, testMode = true, records = listOf(record))))
        val entry = index.records.single()
        val searchable = store.searchable()
        val values = listOf(index, entry, RankedEntry(entry, 1), found(marker)!!, ConversationLookup.Found(found(marker)!!), searchable, search, search.port(RecordRef(id(1), 1)))

        for (value in values) assertFalse(value.javaClass.simpleName, value.toString().contains(marker, ignoreCase = true))
    }
}
