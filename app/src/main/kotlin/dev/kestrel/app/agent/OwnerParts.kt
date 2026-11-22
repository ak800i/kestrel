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
import dev.kestrel.safety.HandOverKind
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

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
 * through [open] and ends when Kestrel's listener connects; a call ends when the Orchestrator's [states] show an outgoing
 * call (Kestrel sees the call state only with READ_PHONE_STATE, and only while it listens).
 */
internal class AppHandOverSignals(
    private val context: Context,
    private val open: suspend (Intent) -> Boolean,
    private val states: Flow<State>,
) : HandOverSignals {
    override suspend fun opened(kind: HandOverKind) {
        if (kind == HandOverKind.ACCESS) open(KestrelNotificationListener.accessSettings(context))
    }

    override suspend fun endedByItself(kind: HandOverKind) {
        when (kind) {
            HandOverKind.ACCESS -> KestrelNotificationListener.connected.first { it }
            HandOverKind.CALL -> awaitOutgoingCall(states)
            else -> awaitCancellation()
        }
    }
}
