package dev.kestrel.app.agent

import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.ConfirmEnd
import dev.kestrel.agent.port.Gate
import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.OwnerPort
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.conversation.TouchWatch
import dev.kestrel.safety.Confirmation
import dev.kestrel.safety.HandOver

/**
 * The owner as one round of a goal talks to him (design D19 of sub-project 5; design 5.5 of sub-project 7): the
 * conversation's [owner], with the goal's touch watcher around it. Every question clears the watcher's credits first, so
 * that the owner's answer by touch is never taken for Kestrel's own action; a hand-over, a permission request and a pause
 * start the watcher again when they end, because an instant command in between stops it (D19). Every `say` text also goes
 * to the round's [witness], which the debug hooks read (plan 6B-basefix).
 */
internal class GoalOwner(
    private val owner: OwnerPort,
    private val watcher: TouchWatch,
    private val witness: TaskWitness,
) : OwnerPort {
    override suspend fun say(text: String) {
        witness.said(text)
        owner.say(text)
    }

    override suspend fun ask(question: String, kind: AnswerKind, choices: List<String>, highest: Int): Answer {
        watcher.forgetOwnActions()
        return owner.ask(question, kind, choices, highest)
    }

    override suspend fun confirm(confirmation: Confirmation): ConfirmEnd {
        watcher.forgetOwnActions()
        return owner.confirm(confirmation)
    }

    override suspend fun handOver(handOver: HandOver): HandOverEnd = owner.handOver(handOver).also { watcher.watch() }

    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd =
        owner.askPermission(permissions, why).also { watcher.watch() }

    override suspend fun gate(): Gate = owner.gate().also { if (it == Gate.RESUMED) watcher.watch() }

    override suspend fun leaveKestrel(): Boolean = owner.leaveKestrel()

    override fun toString(): String = "GoalOwner"
}
