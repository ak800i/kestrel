package dev.kestrel.conversation.engine

/**
 * How the settings page's "Delete history" ended (design 9.7 of sub-project 7): the history is deleted; the request was
 * left alone, because a goal is open or one of Kestrel's own requests runs, as "delete history" by voice is; or the
 * deletion failed, which the engine's report got.
 */
enum class HistoryDeletion { DELETED, REFUSED, FAILED }
