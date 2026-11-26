package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.store.ConversationSummary
import java.time.ZoneId

/** One past conversation in the list (design 9.5 of sub-project 7): its date, its first request and its number of commands. */
internal data class PastRow(val id: ConversationId, val date: String, val request: String, val commands: Int) {
    override fun toString(): String = "PastRow(commands=$commands, request=${request.length} chars)"
}

/** Which past conversations the list holds, and what each row says (design 9.5 of sub-project 7). */
internal object PastList {
    /**
     * The rows for [summaries], the closed conversations: those whose last change is at or after [sinceWall] (within
     * retention), the newest change first, and, while the search field holds words that count, only those in [matching].
     * A row's date is the day on which its conversation began, in [zone]; its request is the first request as the index
     * keeps it, already cut at 80 characters.
     */
    fun rows(summaries: List<ConversationSummary>, sinceWall: Long, matching: Set<ConversationId>?, zone: ZoneId): List<PastRow> =
        summaries
            .filter { it.lastWall >= sinceWall && (matching == null || it.id in matching) }
            .sortedByDescending { it.lastWall }
            .map { PastRow(it.id, ConversationTexts.date(it.openedAtWall, zone), it.firstRequest.orEmpty(), it.commands) }
}
