package dev.kestrel.agent.port

import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver

/**
 * The owner, as the agent talks to him (design D1, D2 of sub-project 5). `:app`'s `AppOwnerPort` (plan 5B) implements it
 * through the Orchestrator; the tests use the fixtures' `FakeOwner`. Questions are asked one at a time.
 */
interface OwnerPort {
    suspend fun say(text: String)

    suspend fun ask(question: String, kind: AnswerKind): Answer

    suspend fun confirm(confirmation: Confirmation): ConfirmEnd

    suspend fun handOver(handOver: HandOver): HandOverEnd

    /** Says [why], shows Android's dialog for [permissions] and waits for its answer (design D12). */
    suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd

    /**
     * Returns [Gate.GO] at once while the task runs; while it is paused (design D2) it suspends and returns
     * [Gate.RESUMED] when the task runs again. A touch or "stop" cancels the agent's job instead.
     */
    suspend fun gate(): Gate
}

/**
 * What kind of answer a question wants. [SCREENSHOT] is the screenshot question of design D20, a free answer that `:app`
 * treats apart, because while another app's audio plays only the card's button may answer it (design D32 item 17;
 * review 4 m7).
 */
enum class AnswerKind { YES_NO, FREE, PICK_NUMBER, SCREENSHOT }

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
