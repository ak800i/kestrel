package dev.kestrel.app.conversation

import dev.kestrel.conversation.engine.ConversationClock
import java.time.ZoneId

/**
 * The phone's wall clock and zone for the conversation (design 6.2 and 6.7 of sub-project 7): stored times are wall-clock
 * times, and the zone names the days of past conversations. The zone is read at each call, so that a change of the phone's
 * zone counts at once.
 */
internal object AndroidConversationClock : ConversationClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
