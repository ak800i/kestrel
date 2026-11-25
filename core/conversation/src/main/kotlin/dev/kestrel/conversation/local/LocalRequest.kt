package dev.kestrel.conversation.local

import dev.kestrel.conversation.model.ConversationId

/**
 * Kestrel's own requests that `LocalRequests` answers without a goal (design 5.2 of sub-project 7). A memory request gets
 * a record, whose request is [words]; the others get none. toString prints no words.
 */
sealed interface LocalRequest {
    val words: String?

    data class Remember(val key: String, val value: String) : LocalRequest {
        override val words: String get() = "Remember that $key is $value"

        override fun toString(): String = "Remember(key=${key.length} chars, value=${value.length} chars)"
    }

    data class Forget(val key: String) : LocalRequest {
        override val words: String get() = "Forget $key"

        override fun toString(): String = "Forget(key=${key.length} chars)"
    }

    data class SaveRoutine(val name: String) : LocalRequest {
        override val words: String get() = "Save that as $name"

        override fun toString(): String = "SaveRoutine(name=${name.length} chars)"
    }

    data object DeleteHistory : LocalRequest {
        override val words: String? get() = null
    }

    data class ContinuePast(val topic: String?) : LocalRequest {
        override val words: String? get() = null

        override fun toString(): String = "ContinuePast(topic=${topic?.length} chars)"
    }
}

/** How a local request ended: its final reply, nothing to say, a switch to a past conversation, or the history deleted. */
sealed interface LocalOutcome {
    val sentence: String?

    data class Reply(override val sentence: String) : LocalOutcome {
        override fun toString(): String = "Reply(${sentence.length} chars)"
    }

    data object Quiet : LocalOutcome {
        override val sentence: String? get() = null
    }

    data class Switch(val id: ConversationId) : LocalOutcome {
        override val sentence: String? get() = null
    }

    data class Deleted(override val sentence: String) : LocalOutcome {
        override fun toString(): String = "Deleted(${sentence.length} chars)"
    }
}
