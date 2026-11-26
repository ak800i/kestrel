package dev.kestrel.app.listen

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Kestrel's accessibility service as Android holds it: [enabled] when Android's setting names it, [running] when Android
 * runs it. Enabled and not running is the state that only the owner can end, by turning the service off and on.
 */
internal data class AccessibilityServiceState(val enabled: Boolean, val running: Boolean)

/**
 * Tells the owner, at the start of Kestrel's process, that Android left Kestrel's accessibility service unbound after he
 * stopped Kestrel (design spec, section 14.6). After Stop in "Active apps" or "Stop app" Android holds the service as
 * crashed while its setting still names it, and binds it again only when the list of enabled services changes, so the
 * service's own notice, which `onUnbind` posts in a living process, never comes: the stop killed the process. A force
 * stop from App info takes the service out of the setting, so the check finds it not enabled and stays quiet.
 *
 * It looks only after Android's REASON_USER_REQUESTED: the owner's stop, or on Android 13 a stop that cannot be told from
 * an update ([ExitReason.UNCLEAR_STOP]; after a real update Android binds the service again, which the state read finds).
 * After a crash, a low-memory kill or a reboot Android binds the service again by itself, at times that it decides (a
 * crashed service's restart waits longer each time), so a check there would tell the owner about a state that ends
 * without him. A stop from before the current boot does not count: a reboot leaves no exit record of its own, and it
 * clears Android's crashed state. The check waits [GRACE_MILLIS] first, because Android may have started the process to
 * bind the service.
 *
 * [service] reads the service's state and [notify] posts the notice, both in [main], the main thread, where the service's
 * `onServiceConnected` cancels the notice: so a bind at the end of the wait either shows in the read or cancels the notice
 * after the post, and never leaves a false notice. [bootedAt] gives the time of the current boot on the wall clock.
 *
 * When the process ends again within the wait, the next start's newest exit record is that end, not the owner's stop, so
 * no start checks again; the setup checklist still shows its warning, and a new stop by the owner brings the check back.
 */
internal class UnboundServiceCheck(
    private val service: () -> AccessibilityServiceState,
    private val notify: () -> Unit,
    private val bootedAt: () -> Long,
    private val main: CoroutineContext,
) {
    /** Checks after the end of the previous process, [exit], which is null when Android has no record. */
    suspend fun afterStart(exit: ExitRecord?) {
        if (exit == null || exit.reason !in STOPS || exit.timestamp < bootedAt()) return
        delay(GRACE_MILLIS)
        withContext(main) {
            val state = service()
            if (state.enabled && !state.running) notify()
        }
    }

    companion object {
        /** How long Android may take to bind a service that it does not hold as crashed: about a second on kestrel-api37. */
        const val GRACE_MILLIS = 10_000L

        // The ends that Android records as REASON_USER_REQUESTED.
        private val STOPS = setOf(ExitReason.OWNER_STOPPED, ExitReason.UNCLEAR_STOP)
    }
}
