package dev.kestrel.app.debug

import dev.kestrel.agent.goal.GoalLimits
import dev.kestrel.conversation.engine.ChecklistSummary
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.engine.RestoreResult
import dev.kestrel.conversation.machine.QuietClock
import dev.kestrel.conversation.model.ConversationSettings

/**
 * The answers of the conversation's hooks (design 14.4 of sub-project 7), apart from Android so that JVM tests check
 * them. They carry counts and kinds only, never words.
 */
internal object ConversationHookAnswers {
    /**
     * conversation-state: whether a conversation is open and was continued, how many commands it holds, its goal's phase,
     * whether Kestrel's own request runs (remember, forget, "save that as", delete history or a switch; plan 7G), its quiet
     * clock with the whole seconds left while the clock runs, the channel of the owner's latest input, the conversation's
     * test mode, and whether the work that a stop would end is the hooks' own ([typed]: the open conversation's mark, or
     * while none is open, that of the request that runs; `HookConversations.ownsWork`, plan 7G). [now] is the wall clock.
     */
    fun state(view: ConversationView, settings: ConversationSettings, now: Long, typed: Boolean): String {
        val (quiet, left) = when (val clock = view.quiet) {
            QuietClock.Off -> "off" to "none"
            is QuietClock.Held -> "held" to "none"
            is QuietClock.Running -> "running" to seconds(settings.quietMillis - (now - clock.sinceWall)).toString()
        }
        return "open=${yesNo(view.id != null)} continued=${yesNo(view.continuedAtWall != null)} commands=${view.records.size} " +
            "goal=${view.goal?.let { it.name.lowercase().replace('_', '-') } ?: "none"} request=${if (view.ownRequest) "running" else "none"} " +
            "quiet=$quiet quietLeft=$left " +
            "replyChannel=${view.replyChannel.name.lowercase()} testMode=${if (view.testMode) "on" else "off"} typed=${yesNo(typed)}"
    }

    /**
     * Why conversation-type may not type words without [tag] when the call's [extras] (the names of its intent's extras)
     * hold one of the options `--es model`, `--ez live` and `--es marker`: only a tag links its options to the typed
     * input, so an untagged one would drop them without a word (Task 13's review M1). Null when it may. The answer names
     * the options that the call gave, never their values.
     */
    fun untaggedOptions(tag: String?, extras: Set<String>): String? {
        val given = TAGGED_OPTIONS.filterKeys { it in extras }.values
        return if (tag != null || given.isEmpty()) null else "add --es tag for ${given.joinToString(", ")}: typed words without a tag take no options"
    }

    // conversation-type's options, which a tag carries to the typed input, by their extras, as a refusal names them.
    private val TAGGED_OPTIONS = linkedMapOf("model" to "--es model", "live" to "--ez live", "marker" to "--es marker")

    /** agent-state's `checklist=`: the goal's done and total items, as "3/7", or none without a checklist. */
    fun checklist(summary: ChecklistSummary?): String = summary?.let { "${it.done}/${it.total}" } ?: "none"

    /**
     * [settings] with a round's limits from [text], "<replies>/<minutes>/<credits>", or the page's smallest without a text,
     * each within the page's bounds (plan 7D's decision 2); null for a text of another form.
     */
    fun limits(settings: ConversationSettings, text: String?): ConversationSettings? {
        val wanted = if (text == null) {
            listOf(ConversationSettings.REPLIES.first, ConversationSettings.WORK_MINUTES.first, ConversationSettings.CREDITS.first)
        } else {
            text.split('/').map { it.trim().toIntOrNull() ?: return null }.takeIf { it.size == 3 } ?: return null
        }
        val limits = GoalLimits(
            wanted[0].coerceIn(ConversationSettings.REPLIES),
            wanted[1].coerceIn(ConversationSettings.WORK_MINUTES),
            wanted[2].coerceIn(ConversationSettings.CREDITS),
        )
        return settings.copy(limits = limits)
    }

    /** A round's limits as conversation-control answers them, and as it reads them back: "<replies>/<minutes>/<credits>". */
    fun limitsText(limits: GoalLimits): String = "${limits.replies}/${limits.workMinutes}/${limits.credits}"

    /** What the restore at the process's start found, as "goal-held"; pending before it has run. */
    fun restored(restored: RestoreResult?): String = restored?.name?.lowercase()?.replace('_', '-') ?: "pending"

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    // Whole seconds, rounded up, and never below 0.
    private fun seconds(millis: Long): Long = (millis.coerceAtLeast(0) + 999) / 1000
}
