package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.store.ConversationSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The list of past conversations (design 9.5 of sub-project 7): which conversations it holds, and what a row says. */
class PastListTest {
    private val zone = ZoneId.of("Europe/Belgrade")
    private val now = ZonedDateTime.of(2026, 11, 20, 18, 4, 0, 0, zone).toInstant().toEpochMilli()

    private fun summary(id: Char, lastWall: Long, request: String?, commands: Int = 1) =
        ConversationSummary(ConversationId(id.toString().repeat(32)), openedAtWall = lastWall - 60_000, lastWall = lastWall, commands = commands, firstRequest = request)

    @Test
    fun theListHoldsTheConversationsWithinRetentionNewestFirstAndWhileTheSearchHasWordsOnlyTheMatchingOnes() {
        val expired = summary('a', now - 31 * DAY, "Open Viber")
        val older = summary('b', now - 2 * DAY, "Read my latest SMS")
        val newer = summary('c', now - DAY, "Find the restaurant Mila sent me")
        val since = now - 30 * DAY

        assertEquals(listOf(newer.id, older.id), PastList.rows(listOf(older, expired, newer), since, null, zone).map { it.id })
        assertEquals(listOf(older.id), PastList.rows(listOf(older, expired, newer), since, setOf(older.id, expired.id), zone).map { it.id })
        assertEquals(emptyList<PastRow>(), PastList.rows(listOf(older, newer), since, emptySet(), zone))
    }

    @Test
    fun aRowHasItsDateItsFirstRequestAsTheIndexCutItAndItsNumberOfCommands() {
        // The index keeps a first request cut at 80 characters (plan 7B's PastIndex.FIRST_REQUEST); the list cuts nothing more.
        val cut = "Find the restaurant that Mila sent me last week, and book a table for four on…"
        val rows = PastList.rows(listOf(summary('a', now, cut, commands = 3), summary('b', now - 1, null)), 0, null, zone)

        assertEquals("Friday, 20 November 2026", rows[0].date)
        assertEquals(cut, rows[0].request)
        assertEquals(3, rows[0].commands)
        assertEquals("", rows[1].request)
    }

    private companion object {
        const val DAY = 24L * 60 * 60 * 1000
    }
}
