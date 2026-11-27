package dev.kestrel.app.conversation

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import dev.kestrel.agent.port.MediaAction
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.R
import dev.kestrel.app.listen.BarOwner
import dev.kestrel.app.listen.OwnedBar
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.phone.ScreenStatus
import dev.kestrel.datatools.media.MediaKeys
import dev.kestrel.datatools.notifications.KestrelNotificationListener
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.HandOverKind
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.overlay.CardEnd
import dev.kestrel.screen.overlay.CardQuestion
import dev.kestrel.screen.overlay.NumberPad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.withIndex
import kotlinx.coroutines.withContext

/** The goal's touch watcher around the owner's questions and hand-overs (design D19 of sub-project 5). */
internal interface TouchWatch {
    /** Clears the touch watcher's credits before a question (rule 3 of D19). */
    fun forgetOwnActions()

    /** Starts the touch watcher again: an instant command during a hand-over or a pause stops it when it ends (D19). */
    suspend fun watch()
}

/** The owner's tap on the card over the app: his [choice], and the screen source's [status] at the tap. */
internal data class ScreenTap(val choice: CardChoice, val status: ScreenStatus)

/** Kestrel's own parts of the screen: the question card, the feedback bar, the lock, and its own app in front. */
internal interface OwnerScreen {
    /**
     * Shows [card] on Kestrel's question card until a button or the keypad answers it, and returns that choice with the
     * screen source's reading and count of screen-offs, taken on the main thread at the tap, in order with the source's
     * reports (design 10.3 of sub-project 7; plan 7F); null when no screen session is connected, or when the card gave no answer because a different question
     * replaced it. Only [CardDesk] calls it: every question has one card.
     */
    suspend fun card(card: OwnerCard): ScreenTap?

    /**
     * The screen source's readings, each with its count of screen-offs, while the flow is collected, the reading first
     * and then every change: the one reading of the lock, by which a card's Continue counts only on an unlocked phone,
     * within the lock that it was tapped in (plan 7E's rule for Continue).
     */
    val screens: Flow<ScreenStatus>

    /**
     * The screen source's status now ([ScreenSource.status]), which stamps the debug hook's press as a tap's own do: on
     * the main thread only, the source's thread, in order with its reports.
     */
    fun screenStatus(): ScreenStatus

    /**
     * How many screen sessions have connected so far; it changes when a new one connects, so that the card desk shows the
     * open card again in it after a card got no answer (plan 7F).
     */
    val connections: Flow<Int>

    /** Lets the feedback bar hide the text that a question or a hand-over kept on it. */
    suspend fun releaseBar()

    /** Whether a window of Kestrel's own app, not one of its overlays, is in front (design 10.2 of sub-project 7). */
    suspend fun kestrelInFront(): Boolean
}

/** The other apps' audio while Kestrel asks (design D32 item 17 of sub-project 5). */
internal interface OtherAudio {
    /** Whether another app's audio of a media or game usage plays: Kestrel's own voice uses the assistant usage. */
    fun playing(): Boolean

    /** Pauses the other app's audio with the media key, as `media_control`'s pause does. */
    suspend fun pause()
}

/** The hand-overs' parts on Android (design D15 of sub-project 5). */
internal interface HandOverSignals {
    /** The hand-over of [kind] has begun; the notification-access hand-over opens Android's page here. */
    suspend fun opened(kind: HandOverKind)

    /**
     * Returns when the hand-over of [kind] ended by itself: notification access when Kestrel's listener connects and
     * Android's page has left the front, a call when the owner placed it. It never returns for the other kinds.
     */
    suspend fun endedByItself(kind: HandOverKind)
}

/** Android's permission dialog for Kestrel's own [permissions] (design D12 of sub-project 5). */
internal fun interface PermissionDialog {
    suspend fun ask(permissions: List<String>): PermissionEnd
}

/**
 * The owner's parts on the phone, which both of the conversation's channels and a goal's rounds share (design 5.5 of
 * sub-project 7): the screen session that is connected ([sessions]; the one when it is asked), Android's audio, the pages
 * that [open] opens for the owner, the phone's [calls] (Kestrel sees them only with READ_PHONE_STATE), and the screen
 * source's readings ([screens]) and its status now ([status]), which stamps each tap.
 */
internal class AndroidOwnerSupport(
    private val context: Context,
    private val sessions: StateFlow<ScreenSession?>,
    private val bar: OwnedBar,
    private val open: suspend (Intent) -> Boolean,
    private val calls: () -> Flow<CallState>,
    override val screens: Flow<ScreenStatus>,
    private val status: () -> ScreenStatus,
) : TouchWatch, OwnerScreen, OtherAudio, HandOverSignals {
    private val audio = context.getSystemService(AudioManager::class.java)

    override val connections: Flow<Int> = sessions.filterNotNull().withIndex().map { it.index + 1 }.onStart { emit(0) }

    private fun session(): ScreenSession? = sessions.value

    override fun screenStatus(): ScreenStatus = status()

    override suspend fun card(card: OwnerCard): ScreenTap? {
        val session = session() ?: return null
        // A pick's button says its choice; every other button says its kind's word (design 10.3 of sub-project 7).
        val labels = card.choices.map { choice ->
            when (choice) {
                is CardChoice.Pick -> card.picks[choice.number - 1]
                else -> context.getString(checkNotNull(choice.label))
            }
        }
        val pad = NumberPad(context.getString(R.string.card_delete_digit), context.getString(R.string.card_enter), context.getString(R.string.card_move), card.highest)
            .takeIf { card.numberPad }
        // The status is taken on the main thread at the tap, in order with the screen source's reports.
        return when (val end = session.ask(CardQuestion(card.key, card.text, labels, pad), status)) {
            is CardEnd.Tapped -> ScreenTap(card.choices[end.button], end.stamp)
            is CardEnd.Number -> ScreenTap(CardChoice.Pick(end.value), end.stamp)
            CardEnd.Withdrawn -> null
        }
    }

    // Through the bar's one writer, so that it lets go of only the question's or the hand-over's own text (plan 7F).
    override suspend fun releaseBar() {
        bar.release(BarOwner.Other)
    }

    override fun forgetOwnActions() {
        session()?.forgetOwnActions()
    }

    // Starting a running watcher only updates its layout and clears the credits.
    override suspend fun watch() {
        session()?.startTouchWatcher()
    }

    // Kestrel's own app in front, by the agent's one rule; its overlays (the bar, the card, the touch watcher) never are.
    override suspend fun kestrelInFront(): Boolean = session()?.let { AgentFreeApps.kestrelInFront(it.snapshots.current().snapshot) } ?: false

    override fun playing(): Boolean = audio.activePlaybackConfigurations.any { it.audioAttributes.usage in OTHER_USAGES }

    override suspend fun pause() {
        MediaKeys(audio).press(MediaAction.PAUSE)
    }

    override suspend fun opened(kind: HandOverKind) {
        if (kind == HandOverKind.ACCESS) open(KestrelNotificationListener.accessSettings(context))
    }

    override suspend fun endedByItself(kind: HandOverKind) {
        when (kind) {
            HandOverKind.ACCESS -> awaitAccessGiven(KestrelNotificationListener.connected, accessPage()) {
                session()?.let { ScreenTree.frontWindow(it.snapshots.current().snapshot)?.packageName }
            }
            HandOverKind.CALL -> awaitOutgoingCall(calls())
            else -> awaitCancellation()
        }
    }

    // The app of Android's notification-access page: Settings on the phones that Kestrel knows.
    private suspend fun accessPage(): String = withContext(Dispatchers.IO) {
        context.packageManager.resolveActivity(KestrelNotificationListener.accessSettings(context), 0)?.activityInfo?.packageName
    } ?: SETTINGS

    override fun toString(): String = "AndroidOwnerSupport"

    private companion object {
        const val SETTINGS = "com.android.settings"
        val OTHER_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
    }
}

/**
 * Returns when the notification-access hand-over ends by itself (design D15; final review of plan 5B, part B, M1):
 * Kestrel's listener is [connected] and Android's page, an app [page], is no longer in [front], because the owner went
 * back, home or to another app. Until then the hand-over stays open, so that the owner's Back or Home on that page, right
 * after he turned access on by touch, does not count as a touch on the goal. "Kestrel, continue" ends it at any time, as
 * the spoken sentence asks.
 */
internal suspend fun awaitAccessGiven(connected: Flow<Boolean>, page: String, front: suspend () -> String?) {
    connected.first { it }
    while (front() == page) delay(FRONT_POLL_MILLIS)
}

/**
 * Returns when [calls] show a call that became active without ringing first, which is a call that the owner placed (the
 * CALL hand-over of design D15 then ends by itself). A call that rang is an incoming call and does not count; once it is
 * over, the next call counts again.
 */
internal suspend fun awaitOutgoingCall(calls: Flow<CallState>) {
    var rang = false
    calls.first { call ->
        when (call) {
            CallState.RINGING -> rang = true
            CallState.ACTIVE -> {}
            CallState.IDLE -> rang = false
        }
        call == CallState.ACTIVE && !rang
    }
}

private const val FRONT_POLL_MILLIS = 300L
