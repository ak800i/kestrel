package dev.kestrel.app.agent

import dev.kestrel.agent.goal.GoalPort
import dev.kestrel.agent.goal.NoGoal
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.DataPort
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.app.conversation.TouchWatch
import dev.kestrel.safety.HandOver
import dev.kestrel.safety.HandOverKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The owner as a goal's round talks to him, with the goal's touch watcher around it (design D19 of sub-project 5). */
class GoalOwnerTest {
    private val owner = FakeOwner()
    private val watcher = CountingWatch()
    private val said = mutableListOf<String>()
    private val witness = object : TaskWitness {
        override fun said(text: String) {
            said += text
        }

        override fun watch(data: DataPort): DataPort = data

        override val evidence: TaskEvidence get() = TaskEvidence(said.toList())
    }
    private val goalOwner = GoalOwner(owner, watcher, witness)

    @Test
    fun everyQuestionClearsTheWatchersCreditsFirstAndKestrelsWordsGoToTheWitness() = runTest {
        goalOwner.say("Android gave Kestrel only the photos that you selected.")
        goalOwner.ask("Which one?", AnswerKind.FREE)

        assertEquals(listOf("Android gave Kestrel only the photos that you selected."), said)
        assertEquals(1, watcher.forgets)
        assertEquals(0, watcher.watches)
    }

    @Test
    fun aHandOverAPermissionRequestAndAResumedGateStartTheWatcherAgain() = runTest {
        owner.handOverEnds.addLast(HandOverEnd.CONTINUED)
        assertEquals(HandOverEnd.CONTINUED, goalOwner.handOver(HandOver.of(HandOverKind.ACCESS)))
        goalOwner.askPermission(listOf("android.permission.READ_CONTACTS"), "I need your contacts.")
        assertEquals(2, watcher.watches)

        owner.gates.addLast(Gate.GO)
        owner.gates.addLast(Gate.RESUMED)
        goalOwner.gate()
        assertEquals(2, watcher.watches)
        goalOwner.gate()
        assertEquals(3, watcher.watches)
    }

    // The instant command that held the screen while the round waited for its turn stopped the watcher, so the round starts
    // it again before it acts (D19; re-review round 2 of plan 7D).
    @Test
    fun aScreenTurnThatWaitedStartsTheWatcherAgain() = runTest {
        val turns = ArrayDeque(listOf(false, true))
        val goal = WatchedGoal(
            object : GoalPort by NoGoal {
                override suspend fun takeScreen(): Boolean = turns.removeFirst()
            },
            watcher,
        )

        assertEquals(false, goal.takeScreen())
        assertEquals(0, watcher.watches)
        assertEquals(true, goal.takeScreen())
        assertEquals(1, watcher.watches)
    }

    private class CountingWatch : TouchWatch {
        var forgets = 0
        var watches = 0

        override fun forgetOwnActions() {
            forgets++
        }

        override suspend fun watch() {
            watches++
        }
    }
}
