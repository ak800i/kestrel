package dev.kestrel.app.debug

import android.content.Intent
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput

/**
 * The conversation's hooks (design 14.4 of sub-project 7): conversation-state reads the open conversation, and
 * conversation-control starts a new one, ends the quiet time, sets a round's limits or says what the restore found. The
 * answers never contain words.
 */
internal class ConversationHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "CONVERSATION_STATE" ->
            ConversationHookAnswers.state(graph.conversations.view.value, graph.settings.get(SettingKeys.CONVERSATIONS), System.currentTimeMillis())
        "CONVERSATION_CONTROL" -> control(intent)
        else -> throw HookError("unknown command '$command'")
    }

    private suspend fun control(intent: Intent): String = when (intent.getStringExtra(EXTRA_DO)) {
        // As the owner's "Kestrel, new conversation" (design 6.8 of sub-project 7): while a goal is open, Kestrel says that
        // it must stop first.
        "new" -> {
            graph.conversations.submit(OwnerInput(InputIds.next(), InputChannel.VOICE, InputIntent.NewConversation, System.currentTimeMillis()))
            "new=sent"
        }
        // The quiet time ends at once if its clock runs, so that a test need not wait for it.
        "expire" -> {
            graph.conversations.endQuietTime()
            "expire=sent"
        }
        "limits" -> limits(intent.getStringExtra(EXTRA_LIMITS))
        "restore-check" -> "restored=${ConversationHookAnswers.restored(graph.conversations.restored)}"
        else -> throw HookError("add --es do with one of new, expire, limits or restore-check")
    }

    // The page's smallest limits, or with --es limits those that a check stored before, so that it can put them back.
    private suspend fun limits(text: String?): String {
        val stored = graph.settings.get(SettingKeys.CONVERSATIONS)
        val changed = ConversationHookAnswers.limits(stored, text)
            ?: throw HookError("add --es limits <replies>/<minutes>/<credits>, or no limits for the page's smallest")
        graph.settings.set(SettingKeys.CONVERSATIONS, changed)
        return "limits=${ConversationHookAnswers.limitsText(changed.roundLimits)} was=${ConversationHookAnswers.limitsText(stored.roundLimits)}"
    }

    companion object {
        val COMMANDS = setOf("CONVERSATION_STATE", "CONVERSATION_CONTROL")
        private const val EXTRA_DO = "do"
        private const val EXTRA_LIMITS = "limits"
    }
}
