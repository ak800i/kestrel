package dev.kestrel.app.conversation.screen

import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.ConversationId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The past conversations' pages (design 9.5 of sub-project 7): where Back goes, and what a deletion's end shows. */
class PastPagesRulesTest {
    private val one = ConversationId("1".repeat(32))
    private val two = ConversationId("2".repeat(32))

    @Test
    fun backGoesFromATranscriptToTheListAndFromTheListOutOfThePages() {
        val list = PastPagesState(query = "trip", notice = PastNotice.DELETE_FAILED)
        val transcript = PastPagesRules.opened(list, one)

        assertEquals(PastPagesState(query = "trip", opened = one), transcript)
        assertEquals(PastPagesState(query = "trip"), PastPagesRules.back(transcript))
        assertNull(PastPagesRules.back(list))
    }

    @Test
    fun aDeletionLeavesItsTranscriptAndCountsAndOtherwiseTheNoticeSaysWhy() {
        val shown = PastPagesState(opened = one, notice = PastNotice.OPEN_NOW)

        assertEquals(PastPagesState(deletions = 1), PastPagesRules.deleted(shown, one, PastDeletion.DELETED))
        assertEquals(PastPagesState(opened = one, deletions = 1), PastPagesRules.deleted(shown, two, PastDeletion.DELETED))
        assertEquals(PastPagesState(opened = one, notice = PastNotice.OPEN_NOW), PastPagesRules.deleted(shown.copy(notice = null), one, PastDeletion.OPEN))
        assertEquals(PastPagesState(opened = one, notice = PastNotice.UNAVAILABLE), PastPagesRules.deleted(shown, one, PastDeletion.UNAVAILABLE))
        assertEquals(PastPagesState(opened = one, notice = PastNotice.DELETE_FAILED), PastPagesRules.deleted(shown, one, PastDeletion.FAILED))
    }
}
