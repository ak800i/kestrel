package dev.kestrel.conversation.testing

import dev.kestrel.agent.port.HandOverEnd
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.conversation.channel.CardAnswer
import dev.kestrel.conversation.channel.ChannelOutput
import dev.kestrel.conversation.channel.ChannelQuestion
import dev.kestrel.conversation.channel.HandOverAnswer
import dev.kestrel.conversation.channel.HandOverPart
import dev.kestrel.conversation.channel.OwnerChannel
import dev.kestrel.conversation.model.ChannelAnswer
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.safety.HandOver
import kotlinx.coroutines.awaitCancellation

/**
 * A channel as the tests script it: it records what it showed and asked; a question, a hand-over or a permission request
 * takes the next queued end, or waits until it is withdrawn, so that an answer can come as an input. [onShow] runs after
 * each output is recorded: a test may hold a reply there, as a voice that still speaks.
 */
class FakeChannel(override val kind: InputChannel) : OwnerChannel {
    val shown = mutableListOf<ChannelOutput>()
    val asked = mutableListOf<ChannelQuestion>()
    val answers = ArrayDeque<ChannelAnswer>()
    val handOvers = mutableListOf<HandOver>()
    val handOverEnds = ArrayDeque<HandOverEnd>()
    val permissionEnds = ArrayDeque<PermissionEnd>()
    var leaves = true
    var onShow: suspend (ChannelOutput) -> Unit = {}

    /** The number of each tap on the channel's card among the owner's inputs, which `:app` takes as the tap reaches Kestrel. */
    var taps: () -> Long = { 0L }

    override suspend fun show(output: ChannelOutput) {
        shown += output
        onShow(output)
    }

    override suspend fun ask(question: ChannelQuestion): CardAnswer {
        asked += question
        val answer = answers.removeFirstOrNull() ?: awaitCancellation()
        return CardAnswer(answer, taps())
    }

    /** How the channel took part in each hand-over, as its own channel or as the copy of its card (plan 7F). */
    val handOverParts = mutableListOf<HandOverPart>()

    // A queued end other than DONE_BY_ITSELF is the owner's tap on the card, which takes its number.
    override suspend fun handOver(handOver: HandOver, part: HandOverPart): HandOverAnswer {
        handOvers += handOver
        handOverParts += part
        val end = handOverEnds.removeFirstOrNull() ?: awaitCancellation()
        return HandOverAnswer(end, if (end == HandOverEnd.DONE_BY_ITSELF) null else taps())
    }

    override suspend fun askPermission(permissions: List<String>, why: String): PermissionEnd =
        permissionEnds.removeFirstOrNull() ?: awaitCancellation()

    override suspend fun leaveKestrel(): Boolean = leaves

    /** The texts of everything shown, in order. */
    fun texts(): List<String> = shown.map {
        when (it) {
            is ChannelOutput.Said -> it.text
            is ChannelOutput.Shown -> it.text
            is ChannelOutput.Report -> it.text
        }
    }

    override fun toString(): String = "FakeChannel($kind, shown=${shown.size}, asked=${asked.size})"
}
