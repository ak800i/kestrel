package dev.kestrel.screen

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.ScreenEvents
import dev.kestrel.screen.read.ScreenReader
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kestrel's screen layer while its accessibility service is connected (design spec, section 9). */
class ScreenSession(service: AccessibilityService) {
    private val reader = ScreenReader(service)
    internal val tracker = ScreenChangeTracker(SystemClock::uptimeMillis)

    /** The current snapshot. A new one is built only after the screen has changed. */
    val snapshots = SnapshotCache(tracker) { withContext(Dispatchers.Default) { reader.read() } }

    /** Kestrel's accessibility service passes every event here. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (ScreenEvents.changesScreen(event.eventType)) tracker.onScreenChanged()
    }
}
