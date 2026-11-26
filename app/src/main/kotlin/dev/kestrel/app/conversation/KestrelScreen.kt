package dev.kestrel.app.conversation

import dev.kestrel.agent.port.OwnerPort
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits until no window of Kestrel's own app but its overlays is in front, at most `OwnerPort.KESTREL_LEAVE_MILLIS`, and
 * returns whether it left (design 10.2 of sub-project 7): both channels' `leaveKestrel`. The conversation screen moves
 * itself to the back once the goal that the owner started on it works (plan 7F); every other screen of Kestrel's waits for
 * the owner to leave it.
 */
internal suspend fun OwnerScreen.awaitKestrelLeft(): Boolean =
    withTimeoutOrNull(OwnerPort.KESTREL_LEAVE_MILLIS) {
        while (kestrelInFront()) delay(KESTREL_POLL_MILLIS)
        true
    } ?: false

/** How often [awaitKestrelLeft] reads whether Kestrel's own screen is still in front. */
internal const val KESTREL_POLL_MILLIS = 200L
