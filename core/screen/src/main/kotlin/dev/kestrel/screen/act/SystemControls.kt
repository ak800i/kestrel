package dev.kestrel.screen.act

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.MagnificationConfig
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.UserHandle
import android.view.accessibility.AccessibilityWindowInfo
import dev.kestrel.screen.read.LiveSnapshot
import dev.kestrel.screen.read.ScreenChangeTracker
import dev.kestrel.screen.read.SnapshotCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Starts activities and system actions, switches to the previous app and pans the magnification (design spec, section
 * 9.3). Like [ScreenActions], it makes blocking binder calls on the caller's dispatcher, so callers run it on
 * `Dispatchers.IO`, and each action waits until the screen has been quiet afterwards, but never longer than 3 s.
 */
class SystemControls(
    private val service: AccessibilityService,
    private val snapshots: SnapshotCache<LiveSnapshot>,
    private val tracker: ScreenChangeTracker,
) {
    /**
     * Starts [intent] from the accessibility service, which Android lets start activities from the background (prototype
     * P4), in a new task.
     */
    suspend fun start(intent: Intent): ActionResult {
        return try {
            service.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            NO_ACTIVITY
        } catch (e: SecurityException) {
            REFUSED
        }
    }

    /** Starts the launcher activity [component] of the profile [user], as the launcher would (research r21). */
    suspend fun openActivity(component: ComponentName, user: UserHandle): ActionResult {
        val launcher = service.getSystemService(LauncherApps::class.java) ?: return NO_ACTIVITY
        return try {
            launcher.startMainActivity(component, user, null, null)
            settled(ActionMethod.INTENT)
        } catch (e: ActivityNotFoundException) {
            NO_ACTIVITY
        } catch (e: SecurityException) {
            REFUSED
        }
    }

    /**
     * Shows the app that was in front before the current one (research r24, prototype P4): two recent-apps actions 300 ms
     * apart, and then a wait of up to 3 s for an app window in front whose app is neither the one before nor the home
     * screen, whose overview shows in between. Kestrel keeps no history of the apps.
     */
    suspend fun previousApp(): ActionResult {
        val before = frontApp()
        val home = homeApp()
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) return RECENTS_REFUSED
        delay(RECENTS_GAP_MILLIS)
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)) return RECENTS_REFUSED
        val switched = withTimeoutOrNull(SWITCH_MILLIS) {
            while (true) {
                val now = activeApp()
                if (now != null && now != before && now != home) break
                delay(POLL_MILLIS)
            }
        } != null
        tracker.onScreenChanged()
        return if (switched) settled(ActionMethod.GLOBAL_ACTION) else NOT_SWITCHED
    }

    /** Performs the system action [id], such as Circle to Search (500) where the launcher offers it (research r25). */
    suspend fun systemAction(id: Int): ActionResult =
        if (service.performGlobalAction(id)) settled(ActionMethod.GLOBAL_ACTION) else ActionResult.Failed("Android refused the system action")

    /**
     * Moves the centre of the magnified area by [dx] and [dy] pixels of the screen; Android keeps the magnified area on the
     * screen. Fails while the screen is not magnified.
     */
    suspend fun pan(dx: Int, dy: Int): ActionResult {
        val controller = service.magnificationController
        val config = controller.magnificationConfig
        if (config == null || config.scale <= 1f) return NOT_MAGNIFIED
        val moved = MagnificationConfig.Builder()
            .setMode(MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN)
            .setScale(config.scale)
            .setCenterX(config.centerX + dx)
            .setCenterY(config.centerY + dy)
            .build()
        val done = withContext(Dispatchers.Main) { controller.setMagnificationConfig(moved, false) }
        if (!done) return ActionResult.Failed("Android refused to move the magnification")
        // As in ScreenActions.zoom: Android sends no event for a move of the magnification, and its node cache keeps the
        // old positions, so Kestrel reports the change itself, before and after the wait.
        tracker.onScreenChanged()
        val result = settled(ActionMethod.MAGNIFICATION)
        service.clearCache()
        tracker.onScreenChanged()
        snapshots.invalidate()
        return result
    }

    /** The centre of the magnified area in screen pixels, or null while the screen is not magnified. */
    fun magnifiedCentre(): Pair<Float, Float>? =
        service.magnificationController.magnificationConfig?.takeIf { it.scale > 1f }?.let { it.centerX to it.centerY }

    /** The ids of the system actions that Android offers now, such as 14 for all apps. */
    fun systemActions(): Set<Int> = service.systemActions.mapTo(HashSet()) { it.id }

    /** The package of the app window that is in front, or null. Kestrel's own overlays are no app windows. */
    private fun activeApp(): String? =
        service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root?.packageName?.toString()

    // The app in front: the active app window, or the top-most one while the shade or a system dialog is active, so that the
    // app under the shade does not count as the previous app.
    private fun frontApp(): String? {
        val apps = service.windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return (apps.firstOrNull { it.isActive } ?: apps.firstOrNull())?.root?.packageName?.toString()
    }

    // The manifest's <queries> lets Kestrel see the home app.
    private fun homeApp(): String? = service.packageManager
        .resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        ?.activityInfo?.packageName

    private suspend fun settled(method: ActionMethod): ActionResult {
        tracker.awaitQuiet(ScreenChangeTracker.ACTION_QUIET_MILLIS, since = tracker.now())
        return ActionResult.Done(method)
    }

    private companion object {
        const val RECENTS_GAP_MILLIS = 300L
        const val SWITCH_MILLIS = 3_000L
        const val POLL_MILLIS = 100L
        val NO_ACTIVITY = ActionResult.Failed("No app can open it")
        val REFUSED = ActionResult.Failed("Android does not let Kestrel open it")
        val RECENTS_REFUSED = ActionResult.Failed("Android refused the recent apps action")
        val NOT_SWITCHED = ActionResult.Failed("No other app came to the front")
        val NOT_MAGNIFIED = ActionResult.Failed("The screen is not magnified")
    }
}
