package dev.kestrel.privacy.conversations

/**
 * The plain fields of a conversation's row, without its payload, as the archive takes and gives them. Its [open] is the
 * one truth about whether the conversation is open (design 6.1 of sub-project 7): the conversation's store reads it
 * from here; the payload does not hold it.
 */
data class ConversationHeader(
    val id: String,
    val openedMillis: Long,
    val lastMillis: Long,
    val open: Boolean,
    val commands: Int,
)
