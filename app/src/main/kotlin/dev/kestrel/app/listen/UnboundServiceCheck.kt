package dev.kestrel.app.listen

import dev.kestrel.conversation.model.ProcessEnd
import kotlinx.coroutines.delay

/**
 * Kestrel's accessibility service as Android holds it: [enabled] when Android's setting names it, [running] when Android
 * runs it. Enabled and not running is the state that only the owner can end, by turning the service off and on.
 */
internal data class AccessibilityServiceState(val enabled: Boolean, val running: Boolean)

/**
 * Tells the owner, at the start of Kestrel's process, that Android left Kestrel's accessibility service unbound after he
 * stopped Kestrel (design spec, section 14.6). After Stop in "Active apps" Android holds the service as crashed while its
 * setting still names it, and binds it again only when the list of enabled services changes, so the service's own
 * notice, which `onUnbind` posts in a living process, never comes: the stop killed the process.
 *
 * Only after the owner's stop: after a crash, a low-memory kill or a reboot Android binds the service again by itself, at
 * times that it decides (a crashed service's restart waits longer each time), so a check there would tell the owner about
 * a state that ends without him. The check waits [GRACE_MILLIS] first, because Android may have started the process to bind
 * the service. [service] reads the service's state, and [notify] posts the notice, which the service cancels when it
 * connects.
 */
internal class UnboundServiceCheck(
    private val service: () -> AccessibilityServiceState,
    private val notify: () -> Unit,
) {
    suspend fun afterStart(end: ProcessEnd) {
        if (end !is ProcessEnd.OwnerStopped) return
        delay(GRACE_MILLIS)
        val state = service()
        if (state.enabled && !state.running) notify()
    }

    companion object {
        /** How long Android may take to bind a service that it does not hold as crashed: about a second on kestrel-api37. */
        const val GRACE_MILLIS = 10_000L
    }
}
