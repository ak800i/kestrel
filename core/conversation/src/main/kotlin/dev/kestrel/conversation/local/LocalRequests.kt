package dev.kestrel.conversation.local

import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.FactKind
import dev.kestrel.agent.observe.ScreenText
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.MemoryStore
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.conversation.ConversationTexts
import dev.kestrel.conversation.channel.Dialogue
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationClock
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.store.ConversationLookup
import dev.kestrel.conversation.store.ConversationStore
import dev.kestrel.conversation.store.HistorySearch
import kotlinx.coroutines.CancellationException

/**
 * Kestrel's own requests, answered without a goal (design 5.2 of sub-project 7). "Remember", "forget" and "save that
 * as" ask through [OwnerPort] and change nothing without the owner's yes (design D22 and D23 of sub-project 5); "save
 * that as" is [routines], the goal runner's. "Delete history" (design 9.7) asks first and then deletes, through the
 * store, the logs, every conversation and the index of past conversations in the archive's one transaction, and the
 * archive's key. "Continue the conversation about X" (design 9.6) finds the best closed conversation within retention,
 * asks whether to switch to it, and gives the switch after a yes; no or no answer changes nothing, and while the index
 * is being rebuilt it says so and asks nothing.
 */
class LocalRequests(
    private val memory: MemoryStore,
    private val store: ConversationStore,
    private val search: HistorySearch,
    private val routines: suspend (name: String, owner: OwnerPort) -> String?,
    private val clock: ConversationClock,
    private val report: (String, Throwable) -> Unit,
) {
    /**
     * Answers [request]: memory requests through [owner], which records them; the others through [dialogue]. [stored]
     * returns once the store holds every write of the conversation queued before the call (the engine's writer): the search
     * and the deletion call it first, so that they see each of those writes.
     */
    suspend fun run(request: LocalRequest, owner: OwnerPort, dialogue: Dialogue, stored: suspend () -> Unit): LocalOutcome = when (request) {
        is LocalRequest.Remember -> remember(request, owner)
        is LocalRequest.Forget -> forget(request, owner)
        is LocalRequest.SaveRoutine -> routines(request.name, owner)?.let { LocalOutcome.Reply(it) } ?: LocalOutcome.Quiet
        LocalRequest.DeleteHistory -> deleteHistory(dialogue, stored)
        is LocalRequest.ContinuePast -> continuePast(request.topic, dialogue, stored)
    }

    private suspend fun remember(request: LocalRequest.Remember, owner: OwnerPort): LocalOutcome {
        if (owner.ask(ConversationTexts.remember(request.key, request.value), AnswerKind.YES_NO) != Answer.Yes) return LocalOutcome.Reply(ConversationTexts.NOTHING_CHANGED)
        memory.saveFact(Fact(request.key, request.value, FactKind.FACT, clock.wallMillis()))
        return LocalOutcome.Reply(ConversationTexts.REMEMBERED)
    }

    private suspend fun forget(request: LocalRequest.Forget, owner: OwnerPort): LocalOutcome {
        val wanted = Normalizer.normalize(request.key)
        val fact = memory.facts().firstOrNull { Normalizer.normalize(it.key) == wanted } ?: return LocalOutcome.Reply(ConversationTexts.noFact(request.key))
        if (owner.ask(ConversationTexts.forget(fact.key), AnswerKind.YES_NO) != Answer.Yes) return LocalOutcome.Reply(ConversationTexts.NOTHING_CHANGED)
        memory.deleteFact(fact.key)
        return LocalOutcome.Reply(ConversationTexts.FORGOTTEN)
    }

    private suspend fun deleteHistory(dialogue: Dialogue, stored: suspend () -> Unit): LocalOutcome {
        val question = ConversationTexts.DELETE_HISTORY_QUESTION
        if (dialogue.question(question, QuestionKind.DELETE_HISTORY, "$question ${ConversationTexts.SAY_YES_OR_NO}") != ChannelAnswer.Yes) return LocalOutcome.Quiet
        return try {
            stored()
            store.deleteAll()
            LocalOutcome.Deleted(ConversationTexts.HISTORY_DELETED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report("Deleting the history", e)
            LocalOutcome.Reply(ConversationTexts.HISTORY_NOT_DELETED)
        }
    }

    private suspend fun continuePast(topic: String?, dialogue: Dialogue, stored: suspend () -> Unit): LocalOutcome {
        stored()
        val match = when (val found = search.conversation(topic)) {
            is ConversationLookup.Found -> found.match
            ConversationLookup.NotFound -> return LocalOutcome.Reply(ConversationTexts.NOT_FOUND)
            ConversationLookup.Rebuilding -> return LocalOutcome.Reply(ConversationTexts.REBUILDING_PAST)
            ConversationLookup.Unavailable -> return LocalOutcome.Reply(ConversationTexts.PAST_UNAVAILABLE)
        }
        val day = ConversationTexts.day(match.openedAtWall, clock.zone())
        val question = ConversationTexts.switchQuestion(day, ScreenText.cut(match.firstRequest, FIRST_REQUEST))
        return if (dialogue.question(question, QuestionKind.SWITCH) == ChannelAnswer.Yes) LocalOutcome.Switch(match.id) else LocalOutcome.Quiet
    }

    override fun toString(): String = "LocalRequests"

    companion object {
        /** The switch question names the conversation's first request cut at this length (design 9.6). */
        const val FIRST_REQUEST = 60
    }
}
