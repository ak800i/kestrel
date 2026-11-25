package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.CommandRecord
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.InputChannel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the voice reads of the conversation (design 5.5 of sub-project 7). */
class VoiceViewTest {
    @Test
    fun theVoiceHearsOfAViewOnlyWhenWhatItReadsChanged() = runTest {
        val acting = ConversationView(id = ConversationId.random(), command = 1, goal = GoalPhase.ACTING)
        val views = flowOf(
            acting,
            // A goal's step and its records are not the voice's.
            acting.copy(step = 3, records = listOf(CommandRecord(1, 0, InputChannel.VOICE, "Read my latest SMS"))),
            acting.copy(goal = GoalPhase.ASKING, asking = QuestionKind.YES_NO, question = 4),
            acting.copy(goal = null, quiet = QuietClock.Held(HoldReason.REPLYING)),
            // One of Kestrel's own requests runs, which "Stop. Kestrel, …" stops first.
            ConversationView(id = acting.id, command = 2, ownRequest = true),
            ConversationView(),
        )

        assertEquals(
            listOf(
                VoiceView(open = true, goal = GoalPhase.ACTING),
                VoiceView(open = true, goal = GoalPhase.ASKING, asking = QuestionKind.YES_NO, question = 4),
                VoiceView(open = true, replying = true),
                VoiceView(open = true, ownRequest = true),
                VoiceView(),
            ),
            views.forVoice().toList(),
        )
    }
}
