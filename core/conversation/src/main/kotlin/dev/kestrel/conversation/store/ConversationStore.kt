package dev.kestrel.conversation.store

import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot

/** The conversations on the phone (design 9.2 of sub-project 7): at most one open, the others closed until retention ends. */
interface ConversationStore {
    /** The open conversation, or null. */
    suspend fun open(): ConversationSnapshot?

    suspend fun save(snapshot: ConversationSnapshot)

    /** The closed conversations, the newest change first (design 9.5). */
    suspend fun list(): List<ConversationSummary>

    suspend fun get(id: ConversationId): ConversationSnapshot?

    /**
     * Makes the closed conversation [id] the open one (D23): [closing], the open conversation as it is now, is stored
     * closed and [id] opened, with [nowWall] as its continuation and its last change and the current [testMode], all in one
     * transaction. Null when [id] is gone or open.
     */
    suspend fun reopen(id: ConversationId, closing: ConversationSnapshot?, nowWall: Long, testMode: Boolean): ConversationSnapshot?

    suspend fun delete(id: ConversationId)

    /** Deletes every conversation, the open one too, and the archive's key (design 9.7). */
    suspend fun deleteAll()

    /** Deletes the closed conversations older than [retentionDays], or dated more than a day ahead; returns how many. */
    suspend fun purge(retentionDays: Int, nowWall: Long): Int

    /** The closed conversations whose last change is at or after [sinceWall], the newest change first. */
    suspend fun closedSince(sinceWall: Long): List<ConversationSnapshot>
}

/** One past conversation in the list: its first request, which the list cuts at 80 characters (design 9.5). */
data class ConversationSummary(
    val id: ConversationId,
    val openedAtWall: Long,
    val lastWall: Long,
    val commands: Int,
    val firstRequest: String?,
) {
    override fun toString(): String = "ConversationSummary(commands=$commands, firstRequest=${firstRequest?.length} chars)"
}
