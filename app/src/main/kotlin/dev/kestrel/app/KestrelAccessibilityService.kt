package dev.kestrel.app

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reads the screen and acts on it for Kestrel (design spec, sections 7.2 and 9). The accessibility button and the
 * accessibility shortcut toggle listening through it (section 8.6). While it is off in a running process, a
 * notification says so (section 14.6). Android calls every callback on the main thread.
 */
class KestrelAccessibilityService : AccessibilityService() {
    private val graph: AppGraph get() = (application as KestrelApplication).graph
    private var session: ScreenSession? = null
    private var purging: Job? = null
    private var button: AccessibilityButtonController.AccessibilityButtonCallback? = null

    override fun onServiceConnected() {
        // The notification that said that this service was off goes (section 14.6).
        graph.problems.accessibilityServiceOn()
        val connected = ScreenSession(this, graph.errors)
        session = connected
        graph.attachScreen(connected)
        // The service runs for days at a time, so it also keeps the logs within their 7 days (design spec, section 13.5).
        purging = graph.scope.launch {
            val context = coroutineContext
            graph.logs.keepPurging { error -> graph.errors.handleException(context, error) }
        }
        // A press of the accessibility button and the accessibility shortcut both arrive here, on the main thread. Toggle
        // switches between listening and paused, or starts Kestrel when it is off (section 8.6).
        val clicks = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                graph.listening.send(Event.Toggle)
            }
        }
        accessibilityButtonController.registerAccessibilityButtonCallback(clicks)
        button = clicks
        // After a restart of Kestrel's process or of the phone, listening comes back from here (sections 8.5 and 14.4).
        graph.listening.onAccessibilityServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        session?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        // Section 14.6: the screen, the button and the shortcut need this service; if Kestrel is listening, it goes on
        // listening. Android calls this about 50 ms after the owner turned the service off (prototype P2), and
        // onDestroy right after it. The notice comes after disconnect(), so that the screen session is detached first.
        graph.problems.accessibilityServiceOff()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        button?.let { accessibilityButtonController.unregisterAccessibilityButtonCallback(it) }
        button = null
        purging?.cancel()
        purging = null
        session?.let {
            graph.detachScreen(it)
            it.close()
        }
        session = null
    }
}
