package dev.kestrel.conversation.model

/** A record of a conversation: its id and the record's number in it. */
data class RecordRef(val conversation: ConversationId, val record: Int)
