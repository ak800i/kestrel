package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.Asked
import dev.kestrel.eval.run.Markers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request's placeholders, the markers and the answers to the task's questions (design D4 of sub-project 6). */
class TaskModelTest {
    @Test
    fun aRequestAndAScriptGetTheRunsValues() {
        val values = RunValues("maple heron", name = "Mira")

        assertEquals("send Mira a Telegram message that I'm 10 minutes late for maple heron", Request("send {name} a Telegram message that I'm 10 minutes late for {marker}").fill(values))
        assertEquals("I told \\\"Mira\\\" ", RunValues("m", name = "\"Mira\"").fill("I told {name} ", json = true))
        assertEquals("The run has no value for {time}.", assertThrows(IllegalStateException::class.java) { values.fill("at {time}") }.message)
        // A link's marker is percent-encoded, so that the link stays one that Kestrel can read.
        assertEquals("mailto:a@example.com?subject=about%20maple%20heron", values.fill("mailto:a@example.com?subject=about%20{marker_url}"))
    }

    @Test
    fun markersAreTwoDifferentWordsWithoutDigitsNeverOneThatWasUsed() {
        val marker = Markers().next(emptyList())

        assertTrue(marker.matches(Regex("[a-z]+ [a-z]+")))
        assertNotEquals(marker.substringBefore(' '), marker.substringAfter(' '))
        val every = Markers.WORDS.flatMap { first -> Markers.WORDS.filter { it != first }.map { "$first $it" } }
        assertEquals("No unused marker is left.", assertThrows(IllegalStateException::class.java) { Markers().next(every) }.message)
    }

    @Test
    fun confirmsOnlyTheCategoriesThatTheTaskNamesDeclinesItsOwnAndCancelsTheRest() {
        val answers = Answers(confirm = setOf("typing_screen_text", "sending"), decline = setOf("deleting"))
        fun reply(state: String) = answers.reply(HookAnswer(state))
        val confirm = Reply.Say("confirm")
        val no = Reply.Say("no")
        val cancel = Reply.Say("cancel")

        assertEquals(confirm, reply("phase=running asking=confirm work=running-task handOver=none confirming=sending"))
        assertEquals(confirm, reply("phase=running asking=confirm work=running-task handOver=none confirming=typing_screen_text"))
        assertEquals(no, reply("phase=running asking=confirm work=running-task handOver=none confirming=deleting"))
        assertEquals(cancel, reply("phase=running asking=confirm work=running-task handOver=none confirming=calls"))
        assertNull(reply("phase=running asking=confirm work=running-task handOver=none confirming=none"))
        assertEquals(no, reply("phase=running asking=yes-no work=running-task handOver=none confirming=none"))
        assertEquals(no, reply("phase=paused asking=continue work=idle handOver=none confirming=none"))
        assertEquals(cancel, reply("phase=running asking=free work=running-task handOver=none confirming=none"))
        assertEquals(cancel, reply("phase=running asking=free work=running-task handOver=none confirming=none question=free"))
        // The owner's decision P1: the screenshot question gets "no", and "Which number should I use?" no answer at all.
        assertEquals(no, reply("phase=running asking=free work=running-task handOver=none confirming=none question=screenshot"))
        assertEquals(Reply.LeaveOpen, reply("phase=running asking=pick-number work=running-task handOver=none confirming=none question=pick-number"))
        assertEquals(cancel, reply("phase=handed-over asking=none work=idle handOver=CREDENTIALS confirming=none"))
        assertNull(reply("phase=running asking=none work=running-task handOver=none confirming=none"))
        assertEquals(cancel, Answers.NONE.reply(HookAnswer("asking=confirm confirming=sending")))

        fun kind(state: String) = Answers.kind(HookAnswer(state))
        assertEquals("screenshot", kind("asking=free question=screenshot handOver=none"))
        assertEquals("free", kind("asking=free question=free handOver=none"))
        assertEquals("pick-number", kind("asking=pick-number question=pick-number handOver=none"))
        assertEquals("confirm/deleting", kind("asking=confirm confirming=deleting handOver=none"))
        assertEquals("hand-over/PERMISSION", kind("phase=handed-over asking=none handOver=PERMISSION"))
        assertEquals("continue-goal", kind("phase=paused asking=continue question=continue-goal handOver=none"))
        assertNull(kind("asking=none handOver=none"))
        assertTrue(Answers.endsTask("free", "cancel") && Answers.endsTask("hand-over/PERMISSION", "cancel") && Answers.endsTask("continue-goal", "no"))
        assertFalse(Answers.endsTask("yes-no", "no") || Answers.endsTask("screenshot", "no") || Answers.endsTask("switch", "no"))
    }

    // Design 7.4, 9.6 and 14.4 of sub-project 7: a limit's "Continue?" gets the task's answers in their order and then
    // none, the switch to a past conversation yes only when the task says so, and "Delete the history?" always no.
    @Test
    fun theConversationsQuestionsGetWhatTheTaskSays() {
        val limit = HookAnswer("phase=running asking=yes-no confirming=none question=limit")
        val answers = Answers(atLimits = listOf(AtLimit.YES, AtLimit.SILENCE), switches = true)

        assertEquals("limit", Answers.kind(limit))
        assertEquals(Reply.Say("yes"), answers.reply(limit))
        assertEquals(Reply.LeaveOpen, answers.reply(limit, listOf(Asked("limit", "yes"))))
        assertEquals(Reply.LeaveOpen, answers.reply(limit, listOf(Asked("limit", "yes"), Asked("limit", null))))
        assertEquals(Reply.LeaveOpen, Answers.NONE.reply(limit))
        // A task that leaves the first "Continue?" to Kestrel's wait answers the next one, once the first one has closed.
        val later = Answers(atLimits = listOf(AtLimit.SILENCE, AtLimit.YES))
        assertEquals(Reply.LeaveOpen, later.reply(limit))
        assertEquals(Reply.Say("yes"), later.reply(limit, listOf(Asked("limit", null))))
        assertEquals(Reply.Say("yes"), answers.reply(HookAnswer("asking=yes-no question=switch")))
        assertEquals(Reply.Say("no"), Answers.NONE.reply(HookAnswer("asking=yes-no question=switch")))
        assertEquals(Reply.Say("no"), answers.reply(HookAnswer("asking=yes-no question=delete-history")))
        assertEquals(Reply.Say("no"), answers.reply(HookAnswer("asking=continue question=continue-goal")))
        assertEquals(
            listOf("switch", "delete-history", "yes-no"),
            listOf("switch", "delete-history", "none").map { Answers.kind(HookAnswer("asking=yes-no question=$it")) },
        )
    }
}
