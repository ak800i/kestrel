package dev.kestrel.conversation.local

import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.FactKind
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.Dialogue
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.context.RecordText
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.ConversationSnapshot
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.store.ArchivedConversationStore
import dev.kestrel.conversation.store.HistorySearch
import dev.kestrel.conversation.testing.ManualConversationClock
import dev.kestrel.conversation.testing.MemoryArchive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class LocalRequestsTest {
    private class FakeDialogue : Dialogue {
        val answers = ArrayDeque<ChannelAnswer>()
        val asked = mutableListOf<Triple<String, QuestionKind, String>>()

        override suspend fun say(text: String) = Unit

        override suspend fun question(text: String, kind: QuestionKind, spoken: String, choices: List<String>, highest: Int): ChannelAnswer {
            asked += Triple(text, kind, spoken)
            return answers.removeFirstOrNull() ?: ChannelAnswer.Silence
        }
    }

    private val day = ArchivedConversationStore.DAY_MILLIS
    private val clock = ManualConversationClock()
    private val archive = MemoryArchive()
    private val scope = TestScope()
    private val store = ArchivedConversationStore(archive, scope.backgroundScope) {}
    private val search = HistorySearch(store, { 30 }, clock)
    private val memory = FakeMemory()
    private val owner = FakeOwner()
    private val dialogue = FakeDialogue()
    private val local = LocalRequests(memory, store, search, { _, _ -> null }, clock) { _, error -> throw error }

    private fun id(n: Int) = ConversationId(n.toString().padStart(32, '0'))

    private suspend fun closed(n: Int, request: String, age: Long) {
        val at = clock.wallMillis() - age
        store.save(ConversationSnapshot(id(n), at, at, open = false, testMode = true, records = listOf(CommandRecord(1, at, InputChannel.TEXT, request))))
    }

    // The answer to forgetting a fact that does not exist is the record's answer: the owner's key is in its record's form
    // (review of plan 7B: C1).
    @Test
    fun forgettingAFactThatDoesNotExistNamesItsKeyInItsRecordsForm() = scope.runTest {
        val outcome = local.run(LocalRequest.Forget("the code 482913"), owner, dialogue) {}

        assertFalse(outcome.sentence!!.contains("482913"))
        assertTrue(outcome.sentence!!.contains("a one-time code"))
    }

    @Test
    fun rememberAsksAndSavesOnlyAfterYes() = scope.runTest {
        owner.answers += Answer.Yes
        assertEquals(LocalOutcome.Reply(ConversationTexts.REMEMBERED), local.run(LocalRequest.Remember("the gate code", "4321"), owner, dialogue) {})
        assertEquals("Remember that the gate code is 4321?", owner.asked.single().first)
        assertEquals(listOf(Fact("the gate code", "4321", FactKind.FACT, clock.wallMillis())), memory.facts)

        owner.answers += Answer.No
        assertEquals(LocalOutcome.Reply(ConversationTexts.NOTHING_CHANGED), local.run(LocalRequest.Remember("the door", "blue"), owner, dialogue) {})
        assertEquals(1, memory.facts.size)
    }

    @Test
    fun forgetFindsTheFactByItsFoldedKey() = scope.runTest {
        memory.facts += Fact("Đorđe birthday", "May 5", FactKind.FACT, 0)
        owner.answers += Answer.Yes

        assertEquals(LocalOutcome.Reply(ConversationTexts.FORGOTTEN), local.run(LocalRequest.Forget("djordje BIRTHDAY"), owner, dialogue) {})
        assertEquals("Forget Đorđe birthday?", owner.asked.single().first)
        assertTrue(memory.facts.isEmpty())
        assertEquals(LocalOutcome.Reply(ConversationTexts.noFact("the gate")), local.run(LocalRequest.Forget("the gate"), owner, dialogue) {})
    }

    @Test
    fun continueTheConversationAboutXAsksAndSwitchesOnYes() = scope.runTest {
        val request = "Plan the trip to Novi Sad and book a hotel near the river for the long weekend"
        closed(1, request, day)
        dialogue.answers += ChannelAnswer.Yes

        assertEquals(LocalOutcome.Switch(id(1)), local.run(LocalRequest.ContinuePast("the trip"), owner, dialogue) {})

        val (text, kind) = dialogue.asked.single()
        assertEquals(QuestionKind.SWITCH, kind)
        assertEquals(ConversationTexts.switchQuestion("Thursday, 19 November", ScreenText.cut(request, LocalRequests.FIRST_REQUEST)), text)
        assertTrue(text.startsWith("Continue the conversation from Thursday, 19 November, about 'Plan the trip"))
    }

    @Test
    fun noAndSilenceChangeNothing() = scope.runTest {
        closed(1, "Plan the trip to Novi Sad", day)
        dialogue.answers += ChannelAnswer.No

        assertEquals(LocalOutcome.Quiet, local.run(LocalRequest.ContinuePast("trip"), owner, dialogue) {})
        assertEquals(LocalOutcome.Quiet, local.run(LocalRequest.ContinuePast("trip"), owner, dialogue) {})
        assertEquals(2, dialogue.asked.size)
    }

    @Test
    fun withoutAMatchKestrelSaysSo() = scope.runTest {
        closed(1, "Plan the trip to Novi Sad", day)

        assertEquals(LocalOutcome.Reply(ConversationTexts.NOT_FOUND), local.run(LocalRequest.ContinuePast("the zoo"), owner, dialogue) {})
        assertTrue(dialogue.asked.isEmpty())
    }

    @Test
    fun withoutATopicTheNewestClosedConversationIsOffered() = scope.runTest {
        closed(1, "Plan the trip to Novi Sad", 2 * day)
        closed(2, "Read my latest SMS", day)
        dialogue.answers += ChannelAnswer.Yes

        assertEquals(LocalOutcome.Switch(id(2)), local.run(LocalRequest.ContinuePast(null), owner, dialogue) {})
        assertTrue(dialogue.asked.single().first.endsWith("about 'Read my latest SMS'?"))
    }

    // Design 9.7 of sub-project 7: the archive's deleteAll is the whole of "delete history", the logs with the
    // conversations in one transaction (plan 7C's archive), so a no deletes nothing and a yes deletes it all through
    // the store.
    @Test
    fun deleteHistoryAsksAndDeletesTheConversationsAndTheWordsAfterYes() = scope.runTest {
        closed(1, "Ambar dinner", day)
        assertEquals(1, search.search(HistoryQuery("Ambar")).matches.size)
        dialogue.answers += listOf(ChannelAnswer.No, ChannelAnswer.Yes)

        assertEquals(LocalOutcome.Quiet, local.run(LocalRequest.DeleteHistory, owner, dialogue) {})
        assertEquals(1, store.list().size)
        assertFalse(archive.keyErased)

        assertEquals(LocalOutcome.Deleted(ConversationTexts.HISTORY_DELETED), local.run(LocalRequest.DeleteHistory, owner, dialogue) {})
        assertTrue(store.list().isEmpty())
        assertTrue(archive.keyErased)
        assertTrue(search.search(HistoryQuery("Ambar")).matches.isEmpty())
        val question = ConversationTexts.DELETE_HISTORY_QUESTION
        assertEquals(Triple(question, QuestionKind.DELETE_HISTORY, "$question ${ConversationTexts.SAY_YES_OR_NO}"), dialogue.asked.first())
    }

    // Design 9.7 of sub-project 7: when the archive's transaction does not commit, nothing is deleted (the logs go in
    // the same transaction), every conversation stays, Kestrel says that it couldn't delete the history, and the
    // failure is reported once.
    @Test
    fun deleteHistoryThatTheArchiveCannotCommitKeepsEveryConversationSaysSoAndReportsOnce() = scope.runTest {
        closed(1, "Ambar dinner", day)
        val failure = IOException("the disk failed")
        archive.failures["deleteAll"] = failure
        val reported = mutableListOf<Throwable>()
        val local = LocalRequests(memory, store, search, { _, _ -> null }, clock) { _, error -> reported += error }
        dialogue.answers += ChannelAnswer.Yes

        assertEquals(LocalOutcome.Reply(ConversationTexts.HISTORY_NOT_DELETED), local.run(LocalRequest.DeleteHistory, owner, dialogue) {})
        assertEquals(1, reported.size)
        assertTrue(reported.single() is IOException)
        assertEquals(failure.message, reported.single().message)
        assertEquals(1, store.list().size)
        assertFalse(archive.keyErased)
    }

    // Design 9.2 and 9.6 of sub-project 7: while the index of past conversations is rebuilt, or cannot be read, Kestrel says
    // so and asks nothing.
    @Test
    fun whileTheIndexIsRebuiltOrCannotBeReadContinuingAPastConversationSaysSoAndAsksNothing() = scope.runTest {
        closed(1, "Plan the trip to Novi Sad", day)
        // The store has written the index; then it is lost.
        store.list()
        archive.shards.clear()
        archive.closedIdsGate = CompletableDeferred()
        val sentences = mapOf(null to ConversationTexts.REBUILDING_PAST, IOException("the disk failed") to ConversationTexts.PAST_UNAVAILABLE)
        for ((failure, sentence) in sentences) {
            failure?.let { archive.failures["readShards"] = it }
            val restarted = ArchivedConversationStore(archive, backgroundScope) {}
            val local = LocalRequests(memory, restarted, HistorySearch(restarted, { 30 }, clock), { _, _ -> null }, clock) { _, error -> throw error }

            assertEquals(LocalOutcome.Reply(sentence), local.run(LocalRequest.ContinuePast("trip"), owner, dialogue) {})
        }
        assertTrue(dialogue.asked.isEmpty())
    }

    @Test
    fun aMemoryRequestsQuestionAndAnswerGoIntoItsRecordRedacted() = scope.runTest {
        val traces = mutableListOf<TraceEntry>()
        owner.answers += Answer.Yes

        local.run(LocalRequest.Remember("the gate code", "482913"), RecordedOwner(owner) { traces += it }, dialogue) {}

        assertEquals(listOf(TraceEntry.Asked(RecordText.of("Remember that the gate code is 482913?")), TraceEntry.Answered("yes")), traces)
        assertFalse((traces.first() as TraceEntry.Asked).text.contains("482913"))
        assertEquals("482913", memory.facts.single().value)
    }
}
