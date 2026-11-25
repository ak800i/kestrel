package dev.kestrel.conversation.model

import dev.kestrel.agent.TaskReport

/**
 * The end of a round of the goal of [command]: its [report]; [answer], Kestrel's final words for it, which the record and
 * the outcome keep; and [reply], what of them is still to say: null when nothing is, as after a routine offer, which said
 * the answer before it asked. Every owner's word in them is in its record's form (design 8.2 of sub-project 7).
 */
data class RoundEnd(val command: Int, val report: TaskReport, val answer: String?, val reply: String?) {
    override fun toString(): String = "RoundEnd(command=$command, report=$report, answer=${answer?.length}, reply=${reply?.length})"
}
