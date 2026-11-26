package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.engine.ConversationClock
import dev.kestrel.conversation.engine.ConversationEngine
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.conversation.store.PastMatches
import dev.kestrel.conversation.store.PastUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A past conversation's transcript, read-only (design 9.5 of sub-project 7), with the date on which it began. */
internal data class PastTranscript(val id: ConversationId, val date: String, val lines: List<TranscriptLine>) {
    override fun toString(): String = "PastTranscript(lines=${lines.size})"
}

/**
 * What the list shows (design 9.5 of sub-project 7): its rows; or that the index of past conversations cannot be read now,
 * which the store tries again by itself later; or that the archive failed, which was reported.
 */
internal sealed interface PastListing {
    data class Rows(val rows: List<PastRow>) : PastListing

    data object Unavailable : PastListing

    data object Failed : PastListing
}

/**
 * The past conversations as the conversation screen shows them (design 9.5 of sub-project 7): the closed ones within the
 * owner's retention ([keptDays]), filtered by the search's own rule, each one's transcript, and their deletion through the
 * engine, which never deletes the open one. The list and the search read plan 7B's index of past conversations and no
 * body; a transcript reads its one body. The store decodes and encodes on its caller's thread, so every read runs on
 * [background], never on the main thread. A failure of the archive is reported to [report] by its class, as the other
 * parts report theirs.
 */
internal class PastConversations(
    private val store: ConversationStore,
    private val search: HistorySearch,
    private val engine: ConversationEngine,
    private val keptDays: () -> Int,
    private val clock: ConversationClock,
    private val report: (String, Throwable) -> Unit,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * The list while the search field holds [text]. It waits while the index is read or rebuilt (the page says "Loading past
     * conversations…"); a search that finds the index rebuilt again reads the list once more.
     */
    suspend fun rows(text: String): PastListing = withContext(background) {
        try {
            var listing: PastListing? = null
            while (listing == null) {
                val summaries = store.list()
                listing = when (val matching = if (text.isBlank()) PastMatches.All else search.matching(text)) {
                    PastMatches.All -> PastListing.Rows(PastList.rows(summaries, sinceWall(), null, clock.zone()))
                    is PastMatches.Only -> PastListing.Rows(PastList.rows(summaries, sinceWall(), matching.ids, clock.zone()))
                    PastMatches.Rebuilding -> null
                    PastMatches.Failed -> PastListing.Unavailable
                }
            }
            listing
        } catch (e: PastUnavailableException) {
            PastListing.Unavailable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Reading past conversations", e)
            PastListing.Failed
        }
    }

    /** Conversation [id]'s transcript, its one body read; null when it is gone, is the open conversation now, or the archive failed. */
    suspend fun transcript(id: ConversationId): PastTranscript? = withContext(background) {
        try {
            store.get(id)?.takeIf { !it.open && it.lastWall >= sinceWall() }?.let { snapshot ->
                PastTranscript(
                    snapshot.id,
                    ConversationTexts.date(snapshot.openedAtWall, clock.zone()),
                    Transcript.of(snapshot.records, snapshot.continuedAtWall, endedAtWall = snapshot.lastWall),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Reading a past conversation", e)
            null
        }
    }

    suspend fun delete(id: ConversationId): PastDeletion = engine.deletePast(id)

    private fun sinceWall(): Long = clock.wallMillis() - keptDays() * ArchivedConversationStore.DAY_MILLIS

    override fun toString(): String = "PastConversations"
}
