package dev.kestrel.app.debug

import android.content.Intent
import android.os.Build
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.conversation.engine.PastDeletion
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput

/**
 * The conversation's hooks (design 14.4 of sub-project 7): conversation-state reads the open conversation and whether
 * Kestrel's own request runs,
 * conversation-control starts a new one, ends the quiet time (with expire-typed only of the hooks' own conversation),
 * sets a round's limits or says what the restore found, and
 * conversation-type types words as the owner does on the conversation screen (plan 7F). The answers never contain words.
 */
internal class ConversationHooks(private val graph: AppGraph) {
    suspend fun run(command: String, intent: Intent): String = when (command) {
        "CONVERSATION_STATE" -> {
            val view = graph.conversations.view.value
            ConversationHookAnswers.state(view, graph.settings.get(SettingKeys.CONVERSATIONS), System.currentTimeMillis(), HookConversations.ownsWork(view.id))
        }
        "CONVERSATION_CONTROL" -> control(intent)
        "CONVERSATION_TYPE" -> type(intent)
        else -> throw HookError("unknown command '$command'")
    }

    // The words go through the text channel, which reads them as the screen does; a tag names the conversation's command
    // that they start, as voice heard's does, with the same options. "yes, call" is refused off the emulator (plan 4D).
    // A conversation that the command's work opens becomes the hooks' own, which delete-typed deletes, by the one rule of
    // the conversation's steps (Task 13a's CommandTags.onConversationStep); one that the command joins, which may be the
    // owner's, does not (review N4, review B-I2). The callers send "do new" first. Options without a tag are refused, because
    // only a tag carries them to the input.
    private suspend fun type(intent: Intent): String {
        val text = intent.getStringExtra(EXTRA_TEXT)?.takeIf { it.isNotBlank() } ?: throw HookError("add --es text with the words to type")
        if (VoiceHooks.refused(text, Build.HARDWARE)) throw HookError(VoiceHooks.YES_CALL_REFUSED)
        val tag = intent.getStringExtra(EXTRA_TAG)
        ConversationHookAnswers.untaggedOptions(tag, intent.extras?.keySet().orEmpty())?.let { throw HookError(it) }
        val channel = graph.textChannel
        val input = channel.read(text) ?: throw HookError("add --es text with the words to type")
        val live = if (intent.hasExtra(EXTRA_LIVE)) intent.getBooleanExtra(EXTRA_LIVE, false) else null
        val options = VoiceHooks.taskOptions(intent.getStringExtra(EXTRA_MODEL), live, intent.getStringExtra(EXTRA_MARKER))
        channel.submit(if (tag != null) VoiceHooks.typed(graph, tag, input, options) else VoiceHooks.untagged(graph, input))
        return "typed read=${VoiceHooks.kindOf(input)}" + (tag?.let { " tag=$it" } ?: "")
    }

    private suspend fun control(intent: Intent): String = when (intent.getStringExtra(EXTRA_DO)) {
        // As the owner's "Kestrel, new conversation" (design 6.8 of sub-project 7): while a goal is open, Kestrel says that
        // it must stop first.
        "new" -> {
            graph.conversations.submit(VoiceHooks.untagged(graph, OwnerInput(InputIds.next(), InputChannel.VOICE, InputIntent.NewConversation, System.currentTimeMillis())))
            "new=sent"
        }
        // The quiet time ends at once if its clock runs, so that a test need not wait for it.
        "expire" -> {
            graph.conversations.endQuietTime()
            "expire=sent"
        }
        // Only the hooks' own conversation (plan 7G): the conversation's loop checks the mark at the moment that it ends the
        // quiet time, so a conversation that the owner opened or that an act of his reached is never ended.
        "expire-typed" -> if (graph.conversations.endQuietTimeIf(HookConversations::owns)) "expire=sent" else "expire=not-typed"
        "limits" -> limits(intent.getStringExtra(EXTRA_LIMITS))
        // A pure read of the stored limits, so that a test's restore asks Kestrel whether its change reached it (plan 7G).
        "limits-check" -> "limits=${ConversationHookAnswers.limitsText(graph.settings.get(SettingKeys.CONVERSATIONS).roundLimits)}"
        "restore-check" -> "restored=${ConversationHookAnswers.restored(graph.conversations.restored)}"
        // The conversations that the hooks opened (HookConversations), never the owner's: each closed one is deleted and its
        // mark cleared, and one that is open or failed stays marked for a later call.
        "delete-typed" -> {
            val ended = HookConversations.all().associateWith { graph.conversations.deletePast(it) }
            HookConversations.forget(ended.filterValues { it == PastDeletion.DELETED }.keys)
            "deleted=${ended.values.count { it == PastDeletion.DELETED }} open=${ended.values.count { it == PastDeletion.OPEN }} " +
                "failed=${ended.values.count { it == PastDeletion.FAILED }}"
        }
        else -> throw HookError("add --es do with one of new, expire, expire-typed, limits, limits-check, restore-check or delete-typed")
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
        val COMMANDS = setOf("CONVERSATION_STATE", "CONVERSATION_CONTROL", "CONVERSATION_TYPE")
        private const val EXTRA_DO = "do"
        private const val EXTRA_LIMITS = "limits"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TAG = "tag"
        private const val EXTRA_MODEL = "model"
        private const val EXTRA_LIVE = "live"
        private const val EXTRA_MARKER = "marker"
    }
}
