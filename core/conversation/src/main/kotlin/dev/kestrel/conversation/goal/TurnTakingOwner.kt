package dev.kestrel.conversation.goal

import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.OwnerPort

/**
 * A goal's round's owner port that takes the screen's turn at every gate (design 6.5 and 7.1 of sub-project 7). The run
 * passes the gate before every action on the screen, every data tool and every question, so a round that goes on after a
 * pause ("Continue the goal?"), a hand-over or a permission request waits for an instant command that holds the screen,
 * as a new round does at its start: [ScreenTurn] is the one rule for who acts on the screen. A turn that waited counts as
 * a resume, so that the run looks at the screen again before it acts.
 */
internal class TurnTakingOwner(private val owner: OwnerPort, private val screen: ScreenTurn) : OwnerPort by owner {
    override suspend fun gate(): Gate {
        val gate = owner.gate()
        return if (screen.take()) Gate.RESUMED else gate
    }

    override fun toString(): String = "TurnTakingOwner($owner)"
}
