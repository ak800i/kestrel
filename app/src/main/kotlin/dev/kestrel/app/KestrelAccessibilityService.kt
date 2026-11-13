package dev.kestrel.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Reads the screen and acts on it for Kestrel (design spec, sections 7.2 and 9). */
class KestrelAccessibilityService : AccessibilityService() {
    private val graph: AppGraph get() = (application as KestrelApplication).graph
    private var session: ScreenSession? = null
    private var purging: Job? = null

    override fun onServiceConnected() {
        val connected = ScreenSession(this, graph.errors)
        session = connected
        graph.attachScreen(connected)
        // The service runs for days at a time, so it also keeps the logs within their 7 days (design spec, section 13.5).
        purging = graph.scope.launch {
            while (isActive) {
                graph.logs.purgeExpired()
                delay(PURGE_INTERVAL_MILLIS)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        session?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        purging?.cancel()
        purging = null
        session?.let {
            graph.detachScreen(it)
            it.close()
        }
        session = null
    }

    private companion object {
        const val PURGE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }
}
