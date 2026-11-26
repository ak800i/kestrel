package dev.kestrel.app.conversation.screen

import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The conversation screen's one reducer (plan 7F). It takes every input of the screen: the conversation's view, what the
 * goal service's coordinator knows ([foreground]), each input that the owner gave on the screen ([sent], which counts only
 * while the screen is in front), the microphone and its listening that heard no command, "New conversation" and whether the screen is in front. After each one it
 * applies [ConversationScreenRules.viewed] to the newest of them, so that no order of their arrival keeps the screen in
 * front of a goal that works. [effects] are what the screen does. It is Android-free; the screen's view model keeps it
 * across configuration changes. Any thread may call it.
 */
internal class ConversationScreenModel(private val nowWall: () -> Long) {
    private val lock = Any()
    private var view = ConversationView()
    private var service = ForegroundState()
    private var front = false
    private val stateNow = MutableStateFlow(ScreenState())
    private val pending = Channel<ScreenEffect>(Channel.UNLIMITED)

    val state: StateFlow<ScreenState> = stateNow.asStateFlow()

    /** What the screen does, in order: leave the front, or give the conversation an input of its own. */
    val effects: Flow<ScreenEffect> = pending.receiveAsFlow()

    fun viewed(view: ConversationView) = step {
        this.view = view
        it
    }

    fun foreground(state: ForegroundState) = step {
        service = state
        it
    }

    fun sent(intent: InputIntent) = step { if (front) ConversationScreenRules.sent(it, intent, view) else it }

    /**
     * The microphone: [listen] asks for the listening and gives its tap's number, in the same step that arms the screen
     * with it, so that the end of the tap's listening never comes before its arm.
     */
    fun listened(listen: () -> Long) = step { ConversationScreenRules.listened(it, view, listen()) }

    /** The listening that the microphone taps [taps] asked for ended without a command for the conversation. */
    fun heardNoCommand(taps: List<Long>) = step { ConversationScreenRules.heardNoCommand(it, taps) }

    fun inFront(inFront: Boolean) = step {
        front = inFront
        if (inFront) it else ConversationScreenRules.left(it)
    }

    fun newConversation() = synchronized(lock) {
        val asked = ConversationScreenRules.newConversation(stateNow.value, view)
        asked.effects.forEach(pending::trySend)
        settle(asked.state)
    }

    override fun toString(): String = "ConversationScreenModel(${stateNow.value}, front=$front)"

    private fun step(change: (ScreenState) -> ScreenState) = synchronized(lock) { settle(change(stateNow.value)) }

    private fun settle(state: ScreenState) {
        val step = ConversationScreenRules.viewed(state, view, service, nowWall())
        stateNow.value = step.state
        step.effects.forEach(pending::trySend)
    }
}
