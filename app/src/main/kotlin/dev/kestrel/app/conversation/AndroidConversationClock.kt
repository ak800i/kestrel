package dev.kestrel.app.conversation

import android.os.SystemClock
import dev.kestrel.conversation.engine.ConversationClock
import java.time.ZoneId

/**
 * The phone's clocks and zone for the conversation (design 6.2 and 6.7 of sub-project 7): stored times are wall-clock
 * times; the conversation's timers are deadlines on the time since the phone started, deep sleep included
 * (`SystemClock.elapsedRealtime()`), so that the quiet time and a held goal's 30 minutes end on time while the phone
 * sleeps; and the zone names the days of past conversations. The zone is read at each call, so that a change of the
 * phone's zone counts at once.
 */
internal object AndroidConversationClock : ConversationClock {
    override fun wallMillis(): Long = System.currentTimeMillis()

    override fun sinceBootMillis(): Long = SystemClock.elapsedRealtime()

    override fun zone(): ZoneId = ZoneId.systemDefault()
}
