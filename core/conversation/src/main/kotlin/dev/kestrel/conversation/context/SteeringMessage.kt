package dev.kestrel.conversation.context

import dev.kestrel.agent.goal.Steer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.PendingSteer
import dev.kestrel.conversation.model.toInput
import dev.kestrel.conversation.model.toSteer

/**
 * One steering message of the owner's (design 7.3 of sub-project 7): [steer], his words as the run takes them (the run
 * redacts them itself), and [recorded], their record's form, made once when the message arrived (`RecordText`). Only the
 * record's form leaves the run: the record's line, the snapshot, and the final words that name the message as too late,
 * so that no vault value reaches a record, the archive or a later model (review of plan 7B: C1). toString prints no words.
 */
class SteeringMessage private constructor(val steer: Steer, val recorded: String) {
    val channel: InputChannel get() = steer.channel.toInput()

    /** The message as the snapshot keeps it, in its record's form. */
    fun pending(): PendingSteer = PendingSteer(recorded, channel)

    override fun toString(): String = "SteeringMessage(${recorded.length} chars, channel=$channel)"

    companion object {
        /** The owner's [text] as it arrives on [channel]. */
        fun of(text: String, channel: InputChannel): SteeringMessage = SteeringMessage(Steer(text, channel.toSteer()), RecordText.of(text))

        /** A message that a snapshot kept, in its record's form, which is then what the run takes too. */
        fun restored(pending: PendingSteer): SteeringMessage = SteeringMessage(Steer(pending.text, pending.channel.toSteer()), pending.text)
    }
}
