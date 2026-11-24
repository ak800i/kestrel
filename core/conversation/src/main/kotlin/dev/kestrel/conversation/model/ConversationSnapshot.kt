package dev.kestrel.conversation.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A conversation as it is stored (design 6.7 and 9.1 of sub-project 7): its state, its records, the goal's phase and the
 * quiet clock's wall-clock start, saved at every change. [steering] holds the owner's steering that no round has taken yet,
 * redacted as a record's text is. [continuedAtWall] is set when the owner continued it (D23). [open] is not in the JSON:
 * the archive's row holds it, and the store reads it from there, so that the row alone says which conversation is open
 * (plan 7C's table keeps at most one). [version] lets a later Kestrel read it. toString prints only counts.
 */
@Serializable
data class ConversationSnapshot(
    val id: ConversationId,
    val openedAtWall: Long,
    val lastWall: Long,
    @Transient val open: Boolean = false,
    val testMode: Boolean,
    val records: List<CommandRecord> = emptyList(),
    val continuedAtWall: Long? = null,
    val goal: GoalState? = null,
    val steering: List<PendingSteer> = emptyList(),
    val quietSinceWall: Long? = null,
    val replyChannel: InputChannel = InputChannel.VOICE,
    val version: Int = VERSION,
) {
    override fun toString(): String =
        "ConversationSnapshot(open=$open, testMode=$testMode, records=${records.size}, goal=$goal, steering=${steering.size}, " +
            "continued=${continuedAtWall != null}, version=$version)"

    companion object {
        const val VERSION = 1
    }
}

/** A steering message that waits for the goal's next turn (design 7.3 of sub-project 7). */
@Serializable
data class PendingSteer(val text: String, val channel: InputChannel) {
    override fun toString(): String = "PendingSteer(${text.length} chars, channel=$channel)"
}
