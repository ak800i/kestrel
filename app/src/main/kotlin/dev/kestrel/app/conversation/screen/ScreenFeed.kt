package dev.kestrel.app.conversation.screen

import dev.kestrel.app.conversation.SentInput
import dev.kestrel.app.goal.ForegroundState
import dev.kestrel.app.listen.CommandWaitEnd
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * What feeds the conversation screen's model, and where its effects go (plan 7F), Android-free so that the screen's
 * view model only names the app's parts. [start] follows the conversation's [view], the goal service's [foreground],
 * each input that the owner gave on the screen ([sent]) and the end of the listening that each assistant tap asked for
 * ([waitEnds]) for as long as [model] lives, and gives the model's own inputs to [submit] at once, so that none waits
 * for a screen that comes back (review of Task 6). Only [leaves] waits for the activity. [visits] counts the screen's
 * arrivals in front, so that the card's buttons arm again on each one (review of Task 7).
 */
internal class ScreenFeed(
    private val model: ConversationScreenModel,
    private val view: StateFlow<ConversationView>,
    private val foreground: Flow<ForegroundState>,
    private val sent: Flow<SentInput>,
    private val waitEnds: Flow<CommandWaitEnd>,
    private val submit: (InputIntent) -> Unit,
) {
    private val leaving = Channel<Unit>(Channel.CONFLATED)
    private val lock = Any()
    private var front = false
    private val visitCount = MutableStateFlow(0)

    /** Each time the screen should leave the front, because the owner's goal works (design 10.2 of sub-project 7). */
    val leaves: Flow<Unit> = leaving.receiveAsFlow()

    /** How many times the screen has come to the front. */
    val visits: StateFlow<Int> = visitCount.asStateFlow()

    fun start(scope: CoroutineScope) {
        scope.launch { view.collect(model::viewed) }
        scope.launch { foreground.collect(model::foreground) }
        scope.launch { sent.collect(model::sent) }
        // The end of the listening that a microphone tap asked for, with an input that starts or resumes work, is the
        // tap's command; any other end is no command. Each end names its taps, so that only the armed tap's own end
        // counts.
        scope.launch {
            waitEnds.collect { end ->
                if (end.input?.let { ConversationScreenRules.leaves(it, view.value) } != true) model.heardNoCommand(end.taps)
            }
        }
        scope.launch {
            model.effects.collect { effect ->
                when (effect) {
                    ScreenEffect.Leave -> leaving.trySend(Unit)
                    is ScreenEffect.Submit -> submit(effect.intent)
                }
            }
        }
    }

    /** Whether the screen is in front; an arrival counts as a new visit. */
    fun inFront(inFront: Boolean) = synchronized(lock) {
        if (inFront && !front) visitCount.value++
        front = inFront
        model.inFront(inFront)
    }

    override fun toString(): String = "ScreenFeed(front=$front, visits=${visitCount.value})"
}
