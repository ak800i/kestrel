package dev.kestrel.app.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.orchestrator.Event

/**
 * Receives the listening notification's Pause, Resume and Stop (design spec, section 8.6). They are broadcasts, not new
 * starts of ListeningService, which already runs in the foreground: a new start would call startForeground() again,
 * which Android may refuse in the background, and a refusal would turn Kestrel off. It is not exported, so only
 * Kestrel's own PendingIntents reach it. Android calls it on the main thread, and it only sends an event.
 */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = when (intent.action) {
            ListeningService.ACTION_PAUSE -> Event.Pause
            ListeningService.ACTION_RESUME -> Event.Resume
            ListeningService.ACTION_STOP -> Event.TurnOff
            else -> return
        }
        (context.applicationContext as KestrelApplication).graph.listening.send(event)
    }
}
