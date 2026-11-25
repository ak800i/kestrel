package dev.kestrel.app.orchestrator

import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.machine.HoldReason
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.GoalPhase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the voice reads of the conversation (design 5.5 of sub-project 7): whether one is [open], its goal's phase, its
 * open question's kind and number, and whether a final reply waits for the owner ([replying], the quiet clock held for
 * it). It holds no words and no records, so a goal's traces and steps never reach the Orchestrator.
 */
data class VoiceView(
    val open: Boolean = false,
    val goal: GoalPhase? = null,
    val asking: QuestionKind? = null,
    val question: Int? = null,
    val replying: Boolean = false,
) {
    companion object {
        fun of(view: ConversationView): VoiceView = VoiceView(
            open = view.id != null,
            goal = view.goal,
            asking = view.asking,
            question = view.question,
            replying = view.quiet == QuietClock.Held(HoldReason.REPLYING),
        )
    }
}

/** The conversation's views as the voice reads them, each one only when what the voice reads changed. */
fun Flow<ConversationView>.forVoice(): Flow<VoiceView> = map(VoiceView::of).distinctUntilChanged()
