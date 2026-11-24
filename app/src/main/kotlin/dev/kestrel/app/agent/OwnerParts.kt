package dev.kestrel.app.agent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import dev.kestrel.agent.port.MediaAction
import dev.kestrel.agent.port.PermissionEnd
import dev.kestrel.app.orchestrator.State
import dev.kestrel.datatools.media.MediaKeys
import dev.kestrel.datatools.notifications.KestrelNotificationListener
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.HandOverKind
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.model.ScreenTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Kestrel's own permissions as Android holds them now (design D12 of sub-project 5). */
internal object Permissions {
    fun granted(context: Context, permissions: List<String>): PermissionEnd {
        val held = permissions.count { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return when (held) {
            permissions.size -> PermissionEnd.GRANTED
            0 -> PermissionEnd.REFUSED
            else -> PermissionEnd.PARTLY
        }
    }
}

/** The screen session that is connected when a question is asked, as [AppOwnerPort] needs it. */
internal class SessionOwnerScreen(private val session: () -> ScreenSession?) : OwnerScreen {
    override suspend fun card(question: String): Boolean? = session()?.confirm(question)

    override fun forgetOwnActions() {
        session()?.forgetOwnActions()
    }

    override suspend fun releaseBar() {
        session()?.releaseFeedback()
    }

    // Starting a running watcher only updates its layout and clears the credits.
    override suspend fun watch() {
        session()?.startTouchWatcher()
    }

    // The front window is an app's; Kestrel's overlays (the bar, the card, the touch watcher) are never in front.
    override suspend fun kestrelInFront(): Boolean =
        session()?.let { ScreenTree.frontWindow(it.snapshots.current().snapshot)?.packageName == AgentFreeApps.KESTREL } ?: false
}

/**
 * The other apps' audio of a media or game usage (design D32 item 17). An ordinary app gets the players without their
 * UIDs, but Kestrel's own voice uses the assistant usage, so any such player is another app's.
 */
internal class AndroidOtherAudio(context: Context) : OtherAudio {
    private val audio = context.getSystemService(AudioManager::class.java)

    override fun playing(): Boolean = audio.activePlaybackConfigurations.any { it.audioAttributes.usage in OTHER_USAGES }

    override suspend fun pause() {
        MediaKeys(audio).press(MediaAction.PAUSE)
    }

    private companion object {
        val OTHER_USAGES = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME)
    }
}

/**
 * The hand-overs that end by themselves (design D9 and D15 of sub-project 5): notification access opens Android's page
 * through [open] and ends when Kestrel's listener connects and that page has left the front ([awaitAccessGiven]), as
 * [front] reads the front app; a call ends when the Orchestrator's [states] show an outgoing call (Kestrel sees the call
 * state only with READ_PHONE_STATE, and only while it listens).
 */
internal class AppHandOverSignals(
    private val context: Context,
    private val open: suspend (Intent) -> Boolean,
    private val states: Flow<State>,
    private val front: suspend () -> String?,
) : HandOverSignals {
    override suspend fun opened(kind: HandOverKind) {
        if (kind == HandOverKind.ACCESS) open(KestrelNotificationListener.accessSettings(context))
    }

    override suspend fun endedByItself(kind: HandOverKind) {
        when (kind) {
            HandOverKind.ACCESS -> awaitAccessGiven(KestrelNotificationListener.connected, accessPage(), front)
            HandOverKind.CALL -> awaitOutgoingCall(states)
            else -> awaitCancellation()
        }
    }

    // The app of Android's notification-access page: Settings on the phones that Kestrel knows.
    private suspend fun accessPage(): String = withContext(Dispatchers.IO) {
        context.packageManager.resolveActivity(KestrelNotificationListener.accessSettings(context), 0)?.activityInfo?.packageName
    } ?: SETTINGS

    private companion object {
        const val SETTINGS = "com.android.settings"
    }
}

/**
 * Returns when the notification-access hand-over ends by itself (design D15; final review of plan 5B, part B, M1):
 * Kestrel's listener is [connected] and Android's page, an app [page], is no longer in [front], because the owner went
 * back, home or to another app. Until then the hand-over stays open, so that the owner's Back or Home on that page, right
 * after he turned access on by touch, does not count as a touch on the running task. "Kestrel, continue" ends it at any
 * time, as the spoken sentence asks.
 */
internal suspend fun awaitAccessGiven(connected: Flow<Boolean>, page: String, front: suspend () -> String?) {
    connected.first { it }
    while (front() == page) delay(FRONT_POLL_MILLIS)
}

private const val FRONT_POLL_MILLIS = 300L
