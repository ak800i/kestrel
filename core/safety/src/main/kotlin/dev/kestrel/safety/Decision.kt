package dev.kestrel.safety

import dev.kestrel.screen.model.IntRect

/** What the safety check decides for a proposal (design D13 of sub-project 5). Every text form prints only kinds and lengths. */
sealed interface Decision {
    data object Run : Decision

    data class Confirm(val confirmation: Confirmation) : Decision

    data class HandOver(val handOver: dev.kestrel.safety.HandOver) : Decision

    /** [reason] is one of Kestrel's fixed sentences, which the model sees after "refused: ". */
    data class Block(val reason: String) : Decision {
        override fun toString(): String = "Block(reasonLength=${reason.length})"
    }
}

/** Why Kestrel stops acting and the owner acts himself (design D15 of sub-project 5). */
enum class HandOverKind { PAYMENT, CREDENTIALS, PERMISSION_DIALOG, CALL, PLAY_SERVICES, RECIPIENT, PERMISSION, ACCESS }

/** A hand-over with Kestrel's own text, which Kestrel speaks and the feedback bar shows. */
data class HandOver(val kind: HandOverKind, val text: String) {
    override fun toString(): String = "HandOver(kind=$kind, textLength=${text.length})"

    companion object {
        /** D15's fixed sentences; [HandOverKind.PERMISSION] and [HandOverKind.ACCESS] get theirs from the agent. */
        fun of(kind: HandOverKind): HandOver = HandOver(
            kind,
            when (kind) {
                HandOverKind.PAYMENT -> "This is a payment. Please finish it yourself, then say 'Kestrel, continue'."
                HandOverKind.CREDENTIALS -> "Please enter your password, then say 'Kestrel, continue'."
                HandOverKind.PERMISSION_DIALOG -> "The app asks for a permission. Please answer it, then say 'Kestrel, continue'."
                HandOverKind.CALL -> "Tap Call yourself if you want this call."
                HandOverKind.PLAY_SERVICES ->
                    "This is a Google Play services screen, which can show passwords. Please finish this step yourself, then say 'Kestrel, continue'."
                HandOverKind.RECIPIENT -> "I can't see who this goes to. Please check it and send it yourself, then say 'Kestrel, continue'."
                HandOverKind.PERMISSION -> "Kestrel needs a permission. Please answer Android's question."
                HandOverKind.ACCESS ->
                    "To read your notifications, Kestrel needs notification access. I'll open the page; turn on Kestrel, then say 'Kestrel, continue'."
            },
        )
    }
}

/**
 * A question that Kestrel's code built from the screen, never from the model's words (design D14 of sub-project 5):
 * [question] for the card, [spoken] for the voice (a long content is cut there), and what the owner confirms, so that
 * the fresh snapshot after his yes can be compared with it ([sameAs]).
 */
data class Confirmation(
    val question: String,
    val spoken: String,
    val category: Category,
    val recipients: List<String>,
    val appLabel: String?,
    val content: String?,
    val targetKey: String?,
    val targetBounds: IntRect?,
) {
    /** The same recipients, app, content, target key and bounds: a yes for one counts for the other. */
    fun sameAs(other: Confirmation): Boolean =
        recipients == other.recipients && appLabel == other.appLabel && content == other.content &&
            targetKey == other.targetKey && targetBounds == other.targetBounds

    override fun toString(): String =
        "Confirmation(category=$category, questionLength=${question.length}, recipients=${recipients.size}, " +
            "contentLength=${content?.length ?: 0}, target=${targetKey != null})"
}

/** Which rule suggested a category or changed a decision, for the activity log's `SAFETY` entry (design D25). */
enum class Source { DECLARED, FIXED, LABEL_WORDS, COMPOSE, PHONE_APP, SHARE_FLOW, PROTECTED_SCREEN, CREDENTIALS, PAYMENTS, TYPING, LINK, PLACEHOLDER, COVERED, TEST_MODE }

/**
 * The safety check's whole answer: the category with its sources, the decision before test mode, which the runs use to
 * judge Standard mode while test mode is on (design D27), and the final decision. [sends] is true when any source
 * suggests sending, also when a higher category wins ("Send and delete"), so that the question names the recipient and
 * test mode checks it (review 2 I2).
 */
data class Assessment(val category: Category, val sources: Set<Source>, val beforeTestMode: Decision, val decision: Decision, val sends: Boolean = false)
