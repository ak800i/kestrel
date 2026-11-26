package dev.kestrel.app.conversation

import dev.kestrel.app.orchestrator.Request
import dev.kestrel.app.orchestrator.Requests
import dev.kestrel.app.orchestrator.VoiceAnswers
import dev.kestrel.conversation.channel.QuestionKind
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.InputIntent

/**
 * The owner's typed words on Kestrel's own screen as an input of the conversation (design 12.2 of sub-project 7), read by
 * plan 7D's typed reading (`Requests.typed`): only the exact name counts, Kestrel's own requests need no name, and the
 * requests that only the voice reads, such as "never mind", "turn off" and "forget it", stay the owner's words. "Stop" and
 * "cancel" always stop. While [view]'s question waits, the words answer it as the voice reads spoken words
 * (`VoiceAnswers`), and the answer names that question's number, so that it never answers a later one (plan 7D's
 * `InputIntent.Answer(answer, question)`); the input's arrival on the text channel tells the conversation that the owner
 * answered on Kestrel's own screen, so the goal looks afresh (design 10.3; plan 7B's `AnswerQuestion`). A confirmation is
 * the exception, because only its card's buttons answer it (design 10.3), so the words reach it as a command, which counts
 * as "no". Null for a text without words.
 */
internal object TypedInputs {
    fun read(text: String, view: ConversationView): InputIntent? {
        val intent = Requests.typed(text) ?: return null
        if (intent == InputIntent.Stop) return intent
        val asking = view.asking
        if (asking != null && asking != QuestionKind.CONFIRM) {
            // The words after the exact name, as the owner typed them.
            val words = (intent as? InputIntent.Command)?.text ?: text.trim()
            VoiceAnswers.read(asking, words, named = false, request = Request.Other(words))?.let { return InputIntent.Answer(it, view.question) }
        }
        return intent
    }
}
