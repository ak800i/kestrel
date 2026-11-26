package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationId

/** What a deletion that did not happen says on the past conversations' pages (design 9.5 of sub-project 7). */
internal enum class PastNotice {
    /** The conversation is the open one now, as after the owner continued it, so it stays. */
    OPEN_NOW,

    /** The index of past conversations cannot be read now (design 9.2); the store tries again by itself later. */
    UNAVAILABLE,

    DELETE_FAILED,
}

/**
 * The past conversations' pages (design 9.5 of sub-project 7): the search field's text, the conversation whose transcript
 * is open, the one that the deletion's question asks about, the notice after a deletion, and how many deletions the list
 * has seen, so that it reads itself again after each. toString leaves the owner's words out.
 */
internal data class PastPagesState(
    val query: String = "",
    val opened: ConversationId? = null,
    val deleting: ConversationId? = null,
    val notice: PastNotice? = null,
    val deletions: Int = 0,
) {
    override fun toString(): String =
        "PastPagesState(query=${query.length} chars, opened=${opened != null}, deleting=${deleting != null}, notice=$notice, deletions=$deletions)"
}

/** How the past conversations' pages change (design 9.5 of sub-project 7); the screen's view model keeps their state. */
internal object PastPagesRules {
    fun opened(state: PastPagesState, id: ConversationId): PastPagesState = state.copy(opened = id, notice = null)

    /** Back: from a transcript to the list, and from the list out of the pages (null). */
    fun back(state: PastPagesState): PastPagesState? = if (state.opened == null) null else state.copy(opened = null, notice = null)

    /** How the deletion of [id] ended: a deleted conversation's transcript closes and the list reads itself again. */
    fun deleted(state: PastPagesState, id: ConversationId, outcome: PastDeletion): PastPagesState = when (outcome) {
        PastDeletion.DELETED -> state.copy(opened = state.opened.takeIf { it != id }, notice = null, deletions = state.deletions + 1)
        PastDeletion.OPEN -> state.copy(notice = PastNotice.OPEN_NOW)
        PastDeletion.UNAVAILABLE -> state.copy(notice = PastNotice.UNAVAILABLE)
        PastDeletion.FAILED -> state.copy(notice = PastNotice.DELETE_FAILED)
    }
}
