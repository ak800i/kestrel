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
 * is open, the one that the deletion's question asks about, the ones whose confirmed deletion the engine has not answered
 * yet ([removing]), whose Delete waits meanwhile, the notice after a deletion, and how many deletions the list has seen, so
 * that it reads itself again after each. toString leaves the owner's words out.
 */
internal data class PastPagesState(
    val query: String = "",
    val opened: ConversationId? = null,
    val deleting: ConversationId? = null,
    val removing: Set<ConversationId> = emptySet(),
    val notice: PastNotice? = null,
    val deletions: Int = 0,
) {
    /** Whether the owner can ask to delete [id]: not while its confirmed deletion runs. */
    fun deletable(id: ConversationId): Boolean = id !in removing

    override fun toString(): String =
        "PastPagesState(query=${query.length} chars, opened=${opened != null}, deleting=${deleting != null}, " +
            "removing=${removing.size}, notice=$notice, deletions=$deletions)"
}

/** How the past conversations' pages change (design 9.5 of sub-project 7); the screen's view model keeps their state. */
internal object PastPagesRules {
    fun opened(state: PastPagesState, id: ConversationId): PastPagesState = state.copy(opened = id, notice = null)

    /** Back: from a transcript to the list, and from the list out of the pages (null). */
    fun back(state: PastPagesState): PastPagesState? = if (state.opened == null) null else state.copy(opened = null, notice = null)

    /** The owner asks to delete [id]: the question comes, unless the deletion of [id] runs already. */
    fun askedDelete(state: PastPagesState, id: ConversationId): PastPagesState =
        if (state.deletable(id)) state.copy(deleting = id) else state

    /** The owner said yes to the question: its conversation is being deleted until [deleted] gives the engine's answer. */
    fun confirmedDelete(state: PastPagesState): PastPagesState =
        state.deleting?.let { state.copy(deleting = null, removing = state.removing + it) } ?: state

    /**
     * How the deletion of [id] ended: a deleted conversation's transcript closes and the list reads itself again, which
     * takes its row away; otherwise the notice says why, and the owner can ask to delete it again.
     */
    fun deleted(state: PastPagesState, id: ConversationId, outcome: PastDeletion): PastPagesState {
        val answered = state.copy(removing = state.removing - id)
        return when (outcome) {
            PastDeletion.DELETED -> answered.copy(opened = state.opened.takeIf { it != id }, notice = null, deletions = state.deletions + 1)
            PastDeletion.OPEN -> answered.copy(notice = PastNotice.OPEN_NOW)
            PastDeletion.UNAVAILABLE -> answered.copy(notice = PastNotice.UNAVAILABLE)
            PastDeletion.FAILED -> answered.copy(notice = PastNotice.DELETE_FAILED)
        }
    }
}
