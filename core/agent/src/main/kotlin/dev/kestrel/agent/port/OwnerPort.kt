package dev.kestrel.agent.port

import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver

/**
 * The owner, as the agent talks to him (design D1, D2 of sub-project 5). `:app`'s `AppOwnerPort` (plan 5B) implements it
 * through the Orchestrator; the tests use the fixtures' `FakeOwner`. Questions are asked one at a time.
 */
interface OwnerPort {
    suspend fun say(text: String)

    /**
     * Asks [question], whose answer is of [kind]. [choices] are a pick's choices, at most [MAX_CHOICES], which the owner's
     * card shows as buttons (design 10.3 of sub-project 7): a tap on choice number i, from 1, answers [Answer.Number] i,
     * as the owner's spoken or typed number i does, so a caller offers choices only when choice i is the answer i. A pick's
     * numbers run from 1 to [highest], which the card's keypad keeps to; 0 when the caller knows no range.
     */
    suspend fun ask(question: String, kind: AnswerKind, choices: List<String> = emptyList(), highest: Int = 0): Answer

    suspend fun confirm(confirmation: Confirmation): ConfirmEnd

    suspend fun handOver(handOver: HandOver): HandOverEnd

    /** Says [why], shows Android's dialog for [permissions] and waits for its answer (design D12). */
    suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd

    /**
     * Returns [Gate.GO] at once while the task runs; while it is paused (design D2) it suspends and returns
     * [Gate.RESUMED] when the task runs again. A touch or "stop" cancels the agent's job instead.
     */
    suspend fun gate(): Gate

    /**
     * Kestrel's own screen is in front of the app that the task would observe (design 10.2 of sub-project 7): waits, as
     * the pause gate does, until no window of Kestrel's but its overlays is in front, at most [KESTREL_LEAVE_MILLIS].
     * True when it has left; false when it stayed, and the task then ends as interrupted.
     */
    suspend fun leaveKestrel(): Boolean

    companion object {
        /** How long [leaveKestrel] waits for Kestrel's own screen to leave (design 10.2 of sub-project 7). */
        const val KESTREL_LEAVE_MILLIS = 3_000L

        /** A pick shows at most this many choices on its card (design 10.3 of sub-project 7). */
        const val MAX_CHOICES = 4
    }
}

/**
 * What kind of answer a question wants. [SCREENSHOT] is the screenshot question of design D20, a free answer that `:app`
 * treats apart, because while another app's audio plays only the card's button may answer it (design D32 item 17;
 * review 4 m7). [LIMIT] is a round's "Continue?" at a limit (design 7.4 of sub-project 7): yes gives another round, and
 * silence, unlike no, leaves a goal open.
 */
enum class AnswerKind { YES_NO, FREE, PICK_NUMBER, SCREENSHOT, LIMIT }

sealed interface Answer {
    data object Yes : Answer

    data object No : Answer

    data class Text(val text: String) : Answer {
        override fun toString(): String = "Text(${text.length} chars)"
    }

    data class Number(val n: Int) : Answer

    data object Silence : Answer

    data object Interrupted : Answer
}

enum class ConfirmEnd { YES, NO, TIMEOUT, INTERRUPTED }

enum class HandOverEnd { CONTINUED, CANCELLED, EXPIRED, DONE_BY_ITSELF }

enum class PermissionEnd { GRANTED, PARTLY, REFUSED }

enum class Gate { GO, RESUMED }
