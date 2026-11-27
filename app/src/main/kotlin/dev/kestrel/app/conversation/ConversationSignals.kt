package dev.kestrel.app.conversation

import dev.kestrel.app.agent.CallBackstop
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.phone.PhoneSignals
import dev.kestrel.app.phone.PhoneWatch
import dev.kestrel.app.phone.ScreenStatus
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.GoalPhase
import dev.kestrel.conversation.model.PhoneSignal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The phone's lock and calls as the conversation's [PhoneSignal]s (design 6.5 of sub-project 7), with listening on or
 * off: while a conversation is open, a goal is, or Kestrel keeps words until the owner is there
 * ([ConversationView.keptForOwner]), it watches [phone] and sends [signal] each change. A call that becomes active without
 * ringing while the goal acts or asks, within the call backstop's window after the agent's last action, is
 * [PhoneSignal.AGENT_CALL] instead of [PhoneSignal.CALL_ACTIVE], and [backstop] ends that call off the caller's thread in
 * [scope] (design D13 of sub-project 5). The first watch in the process sends the lock state that it reads, `UNLOCKED` too
 * (also on a phone without a lock screen), because the conversation counts the owner as there only once a lock signal
 * said so (plan 7B). Later signals go on from the readings that the conversation last got, so a change while nothing
 * watched counts at the next watch. Each time the screen comes on while it watches, locked or not, it sends
 * [PhoneSignal.SCREEN_ON], so that the engine ends the timers whose deadlines passed while the phone slept. Without the permission for the phone state it sees no call.
 */
internal class ConversationSignals(
    private val phone: PhoneSignals,
    private val backstop: CallBackstop,
    private val scope: CoroutineScope,
    private val signal: (PhoneSignal) -> Unit,
    private val report: (String, Throwable) -> Unit,
) {
    private val lock = Any()

    // The lock state that the conversation last got; null until the first reading in the process.
    private var unlocked: Boolean? = null
    private var call = CallState.IDLE
    private var rang = false

    @Volatile
    private var goal: GoalPhase? = null

    /** Follows the conversation's [views] for as long as it runs: it watches the phone while one is open or words wait. */
    suspend fun follow(views: Flow<ConversationView>) {
        var watches = emptyList<PhoneWatch>()
        try {
            views.collect { view ->
                goal = view.goal
                val open = view.id != null || view.goal != null || view.keptForOwner
                if (open && watches.isEmpty()) {
                    watches = watch()
                } else if (!open && watches.isNotEmpty()) {
                    watches.forEach { it.stop() }
                    watches = emptyList()
                }
            }
        } finally {
            watches.forEach { it.stop() }
        }
    }

    private fun watch(): List<PhoneWatch> {
        val screen = phone.watchScreen(::screenCameOn, ::screenChanged)
        val calls = try {
            phone.watchCalls(::callChanged)
        } catch (e: Exception) {
            report("Watching the calls", e)
            null
        }
        return listOfNotNull(screen, calls)
    }

    private fun screenChanged(status: ScreenStatus) {
        val on = status.reading.unlocked
        val changed = synchronized(lock) { (unlocked != on).also { unlocked = on } }
        if (changed) signal(if (on) PhoneSignal.UNLOCKED else PhoneSignal.LOCKED)
    }

    // Locked or not: the engine then ends the timers whose deadlines passed while the phone slept (plan 7B).
    private fun screenCameOn() = signal(PhoneSignal.SCREEN_ON)

    // Android repeats the state at every new watch, so only a change counts.
    private fun callChanged(state: CallState) {
        val sent = synchronized(lock) {
            if (state == call) return
            call = state
            when (state) {
                CallState.RINGING -> PhoneSignal.RINGING.also { rang = true }
                CallState.IDLE -> PhoneSignal.CALL_IDLE.also { rang = false }
                CallState.ACTIVE -> if (!rang && acts() && backstop.stops()) PhoneSignal.AGENT_CALL else PhoneSignal.CALL_ACTIVE
            }
        }
        signal(sent)
        if (sent == PhoneSignal.AGENT_CALL) endCall()
    }

    // The backstop sees only a goal that acts or asks, as it saw only a running task: a call that the owner places
    // during a hand-over or a pause is his.
    private fun acts(): Boolean = goal == GoalPhase.ACTING || goal == GoalPhase.ASKING

    // Ending the call takes binder calls, which must not run on Android's thread.
    private fun endCall() {
        scope.launch {
            try {
                backstop.endCall()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                report("Ending the agent's call", e)
            }
        }
    }

    override fun toString(): String = "ConversationSignals(goal=$goal)"
}
