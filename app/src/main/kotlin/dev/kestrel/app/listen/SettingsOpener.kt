package dev.kestrel.app.listen

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Kestrel, open settings" (design spec, sections 8.6 and 14): opens Kestrel's settings app and waits up to [waitMillis]
 * until it is in front. With Kestrel's accessibility service on ([serviceOn]), Android lets Kestrel start it from the
 * background (prototype P1), and [startActivity] does. With only the assistant role ([roleHeld]), Android 13 most likely
 * blocks such a start while another app is in front (A71 session of sub-project 2, item 5), so [showSession] asks
 * Kestrel's assistant service for a session, which starts the settings as the assistant's activity; it returns whether a
 * service was there to ask. When the settings do not come to the front, [offer] posts a notification that opens them,
 * and [open] says BLOCKED; without the service and the role it does not try. [report] gets the failures that it handles;
 * a failure of [startActivity] goes to the caller. A coroutine of Listening's scope calls [open].
 */
internal class SettingsOpener(
    private val timeline: Timeline,
    private val serviceOn: () -> Boolean,
    private val roleHeld: () -> Boolean,
    private val startActivity: () -> Unit,
    private val showSession: () -> Boolean,
    private val offer: () -> Unit,
    private val report: (String, Throwable) -> Unit,
    private val waitMillis: Long = 2_000L,
) {
    suspend fun open(): SettingsOpening {
        if (timeline.settingsInFront) return SettingsOpening.OPENED
        val service = serviceOn()
        if (!service && !roleHeld()) return SettingsOpening.NEED_SERVICE
        val before = timeline.settingsResumes.value
        timeline.settingsAsked()
        val asked = if (service) {
            startActivity()
            true
        } else {
            try {
                showSession()
            } catch (e: RuntimeException) {
                report("Opening the settings through the assistant", e)
                false
            }
        }
        val resumed = asked && withTimeoutOrNull(waitMillis) { timeline.settingsResumes.first { it > before } } != null
        if (resumed) return SettingsOpening.OPENED
        try {
            offer()
        } catch (e: RuntimeException) {
            report("Posting the notification of the settings", e)
        }
        return SettingsOpening.BLOCKED
    }
}
