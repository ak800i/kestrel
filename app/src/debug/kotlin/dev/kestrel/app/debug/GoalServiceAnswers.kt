package dev.kestrel.app.debug

import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalServiceStatus

/**
 * The answer of the hook goal-service-state (plan 7E; design 10.6 of sub-project 7), apart from Android so that a JVM test
 * checks it: the coordinator's view of the service, the notice that the service shows and the held goal's notice that
 * Kestrel posted without it, the buttons and progress of the notice that counts, the types that the service should have
 * and those that it applied, whether Android runs it in the foreground and with which types ([android], null when it does
 * not), the starts and refusals in this process, and the newest audio-focus request ([focus]). Kinds and counts only,
 * never the notification's words.
 */
internal object GoalServiceAnswers {
    fun state(status: GoalServiceStatus, android: Set<ForegroundType>?, focus: FocusResult?): String {
        val counting = status.notice ?: status.posted
        val focusBy = when {
            focus == null -> "none"
            focus.speaking -> "speak"
            else -> "focus"
        }
        return "service=${status.service.name.lowercase()} notice=${status.notice?.kind?.name?.lowercase() ?: "none"} " +
            "posted=${status.posted?.kind?.name?.lowercase() ?: "none"} " +
            "actions=${counting?.actions?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name.lowercase() } ?: "none"} " +
            "progress=${counting?.progress?.let { "${it.done}/${it.total}" } ?: "none"} " +
            "types=${types(status.types)} applied=${types(status.applied)} android=${if (android == null) "none" else "running"} " +
            "androidTypes=${types(android)} starts=${status.starts} refusals=${status.refusals} " +
            "focus=${focus?.let { if (it.granted) "granted" else "failed" } ?: "none"} focusBy=$focusBy focusTypes=${types(focus?.types)}"
    }

    // "special-use+media-playback"; none without a type, as on Android 13, and without a service.
    private fun types(types: Set<ForegroundType>?): String =
        types?.takeIf { it.isNotEmpty() }?.sortedBy { it.ordinal }?.joinToString("+") { it.wire } ?: "none"
}
