package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.AppUse
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.DataNote
import dev.kestrel.conversation.model.InputChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexTest {
    private fun id(n: Int) = ConversationId(n.toString().padStart(32, '0'))

    private fun conversation(n: Int, vararg requests: String) = ConversationSnapshot(
        id(n), 0, 0, testMode = true,
        records = requests.mapIndexed { index, request -> CommandRecord(index + 1, 10L * index, InputChannel.TEXT, request) },
    )

    private fun SearchIndex.wordsOf(n: Int, record: Int): List<String> = wordsOf(records.single { it.ref == RecordRef(id(n), record) })

    @Test
    fun eachRecordsTextsAreFoldedOnceIntoWordsInOrderAndAscendingPositions() {
        val record = CommandRecord(
            1, 5, InputChannel.TEXT, "Pronađi restoran", answer = "Ambar, Karađorđeva 2.",
            data = listOf(DataNote("list_sms", 1, "from: \"Мила\"", items = 1)), apps = listOf(AppUse("Viber", "com.viber.voip")),
            ledger = listOf("opened Viber"),
        )

        val index = SearchIndex.of(listOf(ConversationSnapshot(id(1), 0, 0, testMode = true, records = listOf(record))))

        assertEquals(index.words.sorted().distinct(), index.words)
        assertEquals(SearchIndex.folded(record).sorted(), index.wordsOf(1, 1))
        assertTrue(listOf("pronadji", "restoran", "ambar", "karadjordjeva", "mila", "viber", "opened").all { it in index.words })
        assertEquals(5L, index.records.single().atWall)
    }

    @Test
    fun anotherIndexJoinsWithItsWordsAndReplacesTheRecordsOfItsConversations() {
        val first = SearchIndex.of(listOf(conversation(1, "Call Ana"), conversation(2, "Read the message from Mila")))
        val second = SearchIndex.of(listOf(conversation(2, "Book the hotel", "Pay the hotel")))

        val joined = first + second

        assertEquals(listOf("ana", "book", "call", "hotel", "pay", "the"), joined.words)
        assertEquals(listOf("ana", "call"), joined.wordsOf(1, 1))
        assertEquals(listOf("book", "hotel", "the"), joined.wordsOf(2, 1))
        assertEquals(listOf("hotel", "pay", "the"), joined.wordsOf(2, 2))
        assertEquals(3, joined.records.size)
        assertSame(first, first + SearchIndex.EMPTY)
    }

    @Test
    fun withoutAConversationItsRecordsAndTheWordsThatOnlyTheyHeldGo() {
        val index = SearchIndex.of(listOf(conversation(1, "Call Ana"), conversation(2, "Call Mila")))

        val left = index.without(setOf(id(2)))

        assertEquals(listOf("ana", "call"), left.words)
        assertEquals(listOf("ana", "call"), left.wordsOf(1, 1))
        assertSame(index, index.without(setOf(id(3))))
    }

    @Test
    fun aWordIsFoundByItsPositionAndTheWordsOfALetterLieTogether() {
        val index = SearchIndex.of(listOf(conversation(1, "milica milan marko ana zoran")))

        assertEquals("milan", index.words[index.position("milan")])
        assertTrue(index.position("mila") < 0)
        assertEquals(listOf("marko", "milan", "milica"), index.startingWith('m').map { index.words[it] })
        assertTrue(index.startingWith('b').isEmpty())
        assertFalse(index.toString().contains("milan"))
    }
}
