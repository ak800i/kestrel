package dev.kestrel.app.conversation.screen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.kestrel.app.AppGraph
import dev.kestrel.app.conversation.AndroidConversationClock
import dev.kestrel.app.conversation.CardChoice
import dev.kestrel.app.conversation.OwnerCard
import dev.kestrel.conversation.engine.ConversationView
import dev.kestrel.conversation.model.ConversationId
import dev.kestrel.conversation.model.InputIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * The conversation screen's state across configuration changes (design 10.1 and 10.2 of sub-project 7; plan 7F): it keeps
 * [model], Task 6's one reducer, and feeds it every input through [ScreenFeed]: the conversation's view, the goal service's
 * foreground state, each input that the text channel gave (also one that the debug hook typed as if on the screen), the
 * microphone and the end of the listening that it started, "New conversation" and whether the screen is in front. The
 * owner's typed words and buttons go only through the text channel (design 12.2). [leaves] asks the activity to leave the
 * front; the model's own inputs, such as "Stop and start new"'s, go to the conversation from here for as long as the
 * model lives, so that a configuration change loses none and none waits for a screen that comes back. [visits] counts
 * the screen's arrivals in front, on each of which the card's buttons arm again.
 */
internal class ConversationScreenViewModel(private val graph: AppGraph) : ViewModel() {
    val model = ConversationScreenModel(AndroidConversationClock::wallMillis)

    private val feed = ScreenFeed(
        model,
        graph.conversations.view,
        graph.goalForeground.foreground,
        graph.textChannel.sent,
        graph.listening.commandWaitEnds,
        ::input,
    )
    private val lock = Any()
    private var words = false
    private var front = false

    val view: StateFlow<ConversationView> get() = graph.conversations.view

    val card: StateFlow<OwnerCard?> get() = graph.cardDesk.open

    /** Each time the screen should leave the front, because the owner's goal works (design 10.2). */
    val leaves: Flow<Unit> get() = feed.leaves

    /** How many times the screen has come to the front: the card's buttons arm again on each visit (review of Task 7). */
    val visits: StateFlow<Int> get() = feed.visits

    val zone: ZoneId get() = AndroidConversationClock.zone()

    private val pages = MutableStateFlow<PastPagesState?>(null)

    /** The past conversations' pages while they are open (design 9.5), kept across configuration changes. */
    val pastPages: StateFlow<PastPagesState?> = pages.asStateFlow()

    val pastConversations: PastConversations get() = graph.pastConversations

    private val replying = MutableStateFlow<Int?>(null)

    /**
     * The question that "Reply…" opened the screen for (design 10.3), kept across a rotation but not across the process,
     * whose question numbers start again.
     */
    val replyTo: StateFlow<Int?> = replying.asStateFlow()

    /** The screen came to the front for question [question], or, with null, for no question. */
    fun reply(question: Int?) {
        replying.value = question
    }

    init {
        feed.start(viewModelScope)
    }

    /** Gives the conversation the owner's typed [text]; false when it held no words. */
    fun send(text: String): Boolean {
        val input = graph.textChannel.read(text) ?: return false
        graph.textChannel.submit(input)
        return true
    }

    /** One of the screen's own inputs: Stop, Continue, Cancel or New conversation. */
    fun input(intent: InputIntent) = graph.textChannel.submit(graph.textChannel.input(intent))

    /** A button on the screen's copy of the open card, which reaches the conversation as an input (design 10.3). */
    fun choose(card: OwnerCard, choice: CardChoice) = graph.textChannel.choose(card.key, choice)

    /** Whether the field holds words: the quiet time is held while it does and the screen is in front (design 6.2). */
    fun field(hasWords: Boolean) = synchronized(lock) {
        words = hasWords
        graph.textChannel.composing(words && front)
    }

    /** Whether the screen is in front: the model, the card desk (the screen's copy or the overlay) and the quiet time follow it. */
    fun inFront(inFront: Boolean) = synchronized(lock) {
        front = inFront
        feed.inFront(inFront)
        graph.cardDesk.screenInFront(inFront)
        graph.textChannel.composing(words && front)
    }

    /**
     * The microphone (design 10.1): as the side key does, Kestrel listens if it is off and waits for one command. The
     * screen's arm ends with the end of this tap's own listening (review M1 of Task 8).
     */
    fun listen() = model.listened(graph.listening::assist)

    fun newConversation() = model.newConversation()

    fun openPast() {
        pages.value = PastPagesState()
    }

    fun closePast() {
        pages.value = null
    }

    /** Changes the open pages; a change to null closes them. */
    fun past(change: (PastPagesState) -> PastPagesState?) = pages.update { it?.let(change) }

    /**
     * The owner said yes to the deletion's question: the engine deletes the conversation, which never is the open one. Its
     * Delete waits from here until the engine's answer comes.
     */
    fun confirmDelete() {
        var id: ConversationId? = null
        past { state ->
            id = state.deleting
            PastPagesRules.confirmedDelete(state)
        }
        val confirmed = id ?: return
        viewModelScope.launch {
            val outcome = graph.pastConversations.delete(confirmed)
            past { PastPagesRules.deleted(it, confirmed, outcome) }
        }
    }

    /** "Continue this conversation" goes through the text channel, as the owner's other inputs do (design 9.6). */
    fun continuePast(id: ConversationId) {
        input(InputIntent.ContinueConversation(id))
        closePast()
    }

    override fun onCleared() {
        inFront(false)
    }

    override fun toString(): String = "ConversationScreenViewModel($model)"
}
