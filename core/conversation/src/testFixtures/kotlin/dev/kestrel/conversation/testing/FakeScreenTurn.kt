package dev.kestrel.conversation.testing

import dev.kestrel.conversation.goal.ScreenTurn
import kotlinx.coroutines.CompletableDeferred

/**
 * The screen's turn as the tests script it (design 6.5 of sub-project 7): [turn] answers each `take` with whether it
 * waited; the fake counts the takes and the releases, and whether the round holds the turn now. A test that sets
 * [command], the owner's instant command that goes on, has the next `take` wait behind it as `ScreenTurns` does: the take
 * says that it waits, waits until the command completes, and counts as a take that waited (plan 7E).
 */
class FakeScreenTurn(private val turn: suspend (after: Long?) -> Boolean = { false }) : ScreenTurn {
    var takes = 0
        private set
    var releases = 0
        private set
    var holds = false
        private set
    var command: CompletableDeferred<Unit>? = null

    override suspend fun take(after: Long?, waiting: () -> Unit): Boolean {
        takes++
        val behind = command?.let { running ->
            command = null
            waiting()
            running.await()
            true
        } ?: false
        val waited = turn(after)
        holds = true
        return waited || behind
    }

    override fun release() {
        releases++
        holds = false
    }

    override fun toString(): String = "FakeScreenTurn(takes=$takes, releases=$releases, holds=$holds, command=${command != null})"
}
