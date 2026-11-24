package dev.kestrel.conversation.model

import dev.kestrel.agent.goal.GoalLimits
import kotlinx.serialization.Serializable

/**
 * The Conversations page (design 13 of sub-project 7), stored encrypted in `SettingKeys.CONVERSATIONS` by `:app`: the quiet
 * time (D18), the follow-up window (D4), whether typed commands get spoken replies (D10), how long past conversations are
 * kept (D6) and one round's limits (D7). The page offers only the choices below; a stored value outside them is read
 * within their bounds, so Kestrel reads [quietMillis], [followUpMillis], [keptDays] and [roundLimits], never the raw fields.
 */
@Serializable
data class ConversationSettings(
    val quietSeconds: Int = 60,
    val followUpSeconds: Int = 8,
    val speakTypedReplies: Boolean = false,
    val retentionDays: Int = 30,
    val limits: GoalLimits = GoalLimits(),
) {
    /** The quiet time between two commands. */
    val quietMillis: Long get() = quietSeconds.coerceIn(QUIET_CHOICES.first(), QUIET_CHOICES.last()) * 1_000L

    /** The follow-up window after a spoken final reply, never longer than the quiet time; 0 is off. */
    val followUpMillis: Long get() = minOf(followUpSeconds.coerceAtLeast(0) * 1_000L, quietMillis)

    /** How many days past conversations are kept. */
    val keptDays: Int get() = retentionDays.coerceIn(RETENTION_CHOICES.first(), RETENTION_CHOICES.last())

    /** One round's limits. */
    val roundLimits: GoalLimits
        get() = GoalLimits(limits.replies.coerceIn(REPLIES), limits.workMinutes.coerceIn(WORK_MINUTES), limits.credits.coerceIn(CREDITS))

    companion object {
        val QUIET_CHOICES = listOf(30, 60, 120, 300, 600, 1_800)
        val FOLLOW_UP_CHOICES = listOf(0, 4, 8, 12, 20, 30)
        val RETENTION_CHOICES = listOf(1, 7, 30, 90, 365)
        val REPLIES = 50..2_000
        val WORK_MINUTES = 5..240
        val CREDITS = 10..5_000
    }
}
