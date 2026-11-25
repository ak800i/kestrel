package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot

/**
 * The conversations on the phone (design 9.2 of sub-project 7): at most one open, the others closed until retention ends.
 * The open conversation is kept in memory; the closed ones are known by the index of past conversations, and a closed
 * one's body is read only when a caller needs it.
 */
interface ConversationStore {
    /**
     * How many times "delete history" ran in this process: [deleteAll] raises it before it deletes anything, also when the
     * deletion does not complete. A reader that takes it before it reads, and compares it before it gives out what it read
     * (the search), never gives out a deleted conversation's words (design 9.7).
     */
    val generation: Long

    /** The open conversation, or null. */
    suspend fun open(): ConversationSnapshot?

    /** Stores [snapshot]; storing an open one closes the conversation that was open (design 6.1). */
    suspend fun save(snapshot: ConversationSnapshot)

    /**
     * The closed conversations, the newest change first (design 9.5), from the index of past conversations, so that no body
     * is read. It waits while the index is read, and also while it is rebuilt; while the index cannot be read, it throws
     * [PastUnavailableException].
     */
    suspend fun list(): List<ConversationSummary>

    /** The conversation [id]: the open one from memory, a closed one read from its row. Null when it is gone. */
    suspend fun get(id: ConversationId): ConversationSnapshot?

    /** The conversations [ids] that exist: the open one from memory, the closed ones read from their rows in one call. */
    suspend fun getAll(ids: Collection<ConversationId>): Map<ConversationId, ConversationSnapshot>

    /**
     * Makes the closed conversation [id] the open one (D23): [closing], the open conversation as it is now, is stored
     * closed and [id] opened, with [nowWall] as its continuation and its last change and the current [testMode], all in one
     * transaction. Null when [id] is gone or open.
     */
    suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot?

    suspend fun delete(id: ConversationId)

    /**
     * Deletes every conversation, the open one too, the index and the archive's key, in the archive alone (design 9.7). Once
     * the archive's deletion has started, a cancellation takes effect only after it, and the outcome is a completed
     * deletion. When the archive's call fails, it has marked nothing, and every conversation stays as the archive has it.
     */
    suspend fun deleteAll()

    /**
     * Deletes the closed conversations older than [retentionDays], or dated more than a day ahead, by the table's own last
     * changes, so that retention holds also while the index is read, rebuilt or cannot be read (design 9.3); returns how
     * many. It never waits for the index.
     */
    suspend fun purge(retentionDays: Int, nowWall: Long): Int

    /**
     * What the search reads (design 9.4): the index of past conversations and the open conversation, or the mark that the
     * index is being rebuilt ([Searchable.Rebuilding]) or cannot be read now ([Searchable.Failed]). It waits while the index
     * is read, never for a rebuild.
     */
    suspend fun searchable(): Searchable
}

/** One past conversation in the list: its first request, cut at 80 characters as the index keeps it (design 9.5). */
data class ConversationSummary(
    val id: ConversationId,
    val openedAtWall: Long,
    val lastWall: Long,
    val commands: Int,
    val firstRequest: String?,
) {
    override fun toString(): String = "ConversationSummary(commands=$commands, firstRequest=${firstRequest?.length} chars)"
}

/**
 * What the search reads: the index of past conversations with the open conversation, or the mark that the index is being
 * rebuilt, or that it could not be read or rebuilt, which the store has noted and tries again later (design 9.2).
 */
sealed interface Searchable {
    class Ready(val past: PastIndex, val open: ConversationSnapshot?) : Searchable {
        override fun toString(): String = "Ready(past=$past, open=$open)"
    }

    data object Rebuilding : Searchable

    data object Failed : Searchable
}

/**
 * The index of past conversations could not be read or rebuilt (design 9.2 of sub-project 7): the list cannot be shown
 * now. The store has noted it once, by its kind, and tries again later by itself. Its message holds no word of a
 * conversation.
 */
class PastUnavailableException(cause: Throwable) : Exception("The index of past conversations cannot be read now", cause)
