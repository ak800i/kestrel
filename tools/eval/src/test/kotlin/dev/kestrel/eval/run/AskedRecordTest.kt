package dev.kestrel.eval.run

import org.junit.Assert.assertEquals
import org.junit.Test

class AskedRecordTest {
    private fun AskedRecord.text() = asked.joinToString(",")

    @Test
    fun aLostAnswerCountsOnlyWhenTheQuestionClosedOrTheTaskEnded() {
        val closed = AskedRecord()
        closed.saw("yes-no")
        closed.lost("no")
        closed.saw(null)
        assertEquals("yes-no:no", closed.text())

        // The same question still open: Kestrel never got the answer, so only the next one counts.
        val stillOpen = AskedRecord()
        stillOpen.saw("free")
        stillOpen.lost("cancel")
        stillOpen.saw("free")
        assertEquals("", stillOpen.text())
        stillOpen.answered("cancel")
        assertEquals("free:cancel", stillOpen.text())
        assertEquals(listOf("free"), stillOpen.cancelled())

        val replaced = AskedRecord()
        replaced.saw("screenshot")
        replaced.lost("no")
        replaced.saw("pick-number")
        replaced.keepOpen()
        assertEquals("screenshot:no,pick-number:none", replaced.text())

        val ended = AskedRecord()
        ended.saw("hand-over/PERMISSION")
        ended.lost("cancel")
        ended.ended()
        assertEquals(listOf("hand-over/PERMISSION"), ended.cancelled())
    }

    @Test
    fun separateQuestionsOfTheSameKindAreSeparateEntries() {
        val record = AskedRecord()
        // Two yes-or-no questions, each answered "no".
        record.saw("yes-no")
        record.answered("no")
        record.saw("yes-no")
        record.answered("no")
        // "Which number should I use?" seen open at two reads is one question; asked again after it closed, it is another.
        record.saw("pick-number")
        record.keepOpen()
        record.saw("pick-number")
        record.keepOpen()
        record.saw(null)
        record.saw("pick-number")
        record.keepOpen()
        // An answer that Kestrel refused, then the same answer sent again to the same open question, is one entry.
        record.saw("free")
        record.saw("free")
        record.answered("cancel")
        assertEquals("yes-no:no,yes-no:no,pick-number:none,pick-number:none,free:cancel", record.text())
    }

    // Plan 7G: the questions before the open one leave out the entry that keeps the open one open, so that each read of the
    // same question counts the same questions before it.
    @Test
    fun theQuestionsBeforeTheOpenOneLeaveOutTheEntryThatKeepsItOpen() {
        val record = AskedRecord()
        record.saw("limit")
        record.answered("yes")
        record.saw("limit")
        record.keepOpen()

        assertEquals(listOf(Asked("limit", "yes")), record.before)
        assertEquals(listOf(Asked("limit", "yes"), Asked("limit", null)), record.asked)
        record.saw(null)
        assertEquals(record.asked, record.before)
    }
}
