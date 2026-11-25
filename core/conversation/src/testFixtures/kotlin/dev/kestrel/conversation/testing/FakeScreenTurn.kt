package dev.kestrel.conversation.testing

import dev.kestrel.conversation.goal.ScreenTurn

/**
 * The screen's turn as the tests script it (design 6.5 of sub-project 7): [turn] answers each `take` with whether it
 * waited; the fake counts the takes and the releases, and whether the round holds the turn now.
 */
class FakeScreenTurn(private val turn: suspend (after: Long?) -> Boolean = { false }) : ScreenTurn {
    var takes = 0
        private set
    var releases = 0
        private set
    var holds = false
        private set

    override suspend fun take(after: Long?): Boolean {
        takes++
        return turn(after).also { holds = true }
    }

    override fun release() {
        releases++
        holds = false
    }

    override fun toString(): String = "FakeScreenTurn(takes=$takes, releases=$releases, holds=$holds)"
}
