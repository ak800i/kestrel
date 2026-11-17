package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Intent
import android.os.UserHandle
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache

/**
 * Starts activities and system actions, switches to the previous app and pans the magnification (design spec, section
 * 9.3). Plan 3C replaces this stub, which does nothing, and keeps its constructor and signatures.
 */
@Suppress("unused")
class SystemControls(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val tracker: ScreenChangeTracker,
) {
    suspend fun start(intent: Intent): ActionResult = NOT_AVAILABLE

    suspend fun openActivity(component: ComponentName, user: UserHandle): ActionResult = NOT_AVAILABLE

    suspend fun previousApp(): ActionResult = NOT_AVAILABLE

    suspend fun systemAction(id: Int): ActionResult = NOT_AVAILABLE

    suspend fun pan(dx: Int, dy: Int): ActionResult = NOT_AVAILABLE

    fun systemActions(): Set<Int> = emptySet()

    private companion object {
        val NOT_AVAILABLE = ActionResult.Failed("Not available yet")
    }
}
