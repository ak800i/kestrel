package dev.kestrel.app.debug

import dev.kestrel.app.goal.ForegroundType
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalServiceStatus

/**
 * The answers of the hooks goal-service-state and goal-control (plan 7E; design 10.6 of sub-project 7), apart from Android
 * so that a JVM test checks them. goal-service-state's: the coordinator's view of the service, the notice that the service
 * shows and the held goal's notice that Kestrel posted without it, the buttons and progress of the notice that counts, the
 * types that the service should have and those that it applied, whether Android runs it in the foreground and with which
 * types ([android], null when it does not), the starts and refusals in this process, whether a refusal of the next start
 * is armed, and the audio-focus requests sent and answered with the newest result ([focus]). Kinds, counts and flags
 * only, never the notification's words.
 */
internal object GoalServiceAnswers {
    fun state(status: GoalServiceStatus, android: Set<ForegroundType>?, focus: FocusRecord, refuseArmed: Boolean): String {
        val counting = status.counting?.notice
        val last = focus.last
        val focusBy = when {
            last == null -> "none"
            last.speaking -> "speak"
            else -> "focus"
        }
        return "service=${status.service.name.lowercase()} notice=${status.notice?.kind?.name?.lowercase() ?: "none"} " +
            "posted=${status.posted?.kind?.name?.lowercase() ?: "none"} " +
            "actions=${counting?.actions?.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name.lowercase() } ?: "none"} " +
            "progress=${counting?.progress?.let { "${it.done}/${it.total}" } ?: "none"} " +
            "types=${types(status.types)} applied=${types(status.applied)} android=${if (android == null) "none" else "running"} " +
            "androidTypes=${types(android)} starts=${status.starts} refusals=${status.refusals} " +
            "refuse=${if (refuseArmed) "armed" else "none"} " +
            "focus=${last?.let { if (it.granted) "granted" else "failed" } ?: "none"} focusBy=$focusBy focusTypes=${types(last?.types)} " +
            "focusRequests=${focus.requests} focusAnswered=${focus.answered}"
    }

    /**
     * goal-control's press in SystemUI's shade, which closes the shade again however the press ended:
     * `tapped=<action> expanded=<rows> shade=closed|open claim=used|dropped|none`. [expanded] names the collapsed rows that
     * the hook asked to expand before the press, in their order, each once: `none` when the row showed its buttons without
     * any expansion, as design 10.6 of sub-project 7 asks, else `row`, `group` or `group,row` ([ShadeExpansion]).
     * [shadeClosed] only when the screen showed the shade closed: `shade=open` also when the hook could not tell
     * ([NoticeButtons.shadeOpen]), so that a caller collapses the shade. [claim] is what the press's claim became before the
     * answer ([ButtonClaims.Settled]; plan 7G): `used` by the button's broadcast, `dropped` when none came before its end,
     * or `none` when the press armed none, as Open's.
     */
    fun tapped(action: GoalAction, expanded: List<ShadeExpansion>, shadeClosed: Boolean, claim: ButtonClaims.Settled): String =
        "tapped=${action.name.lowercase()} expanded=${expanded.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name.lowercase() } ?: "none"} " +
            "${shade(shadeClosed)} claim=${claim.wire}"

    /**
     * The error of a press that did not happen or may not have worked, for the [reason] (a hook's own sentence, never the
     * screen's words), with the shade and what the press's claim became, as [tapped] says them.
     */
    fun notTapped(reason: String, shadeClosed: Boolean, claim: ButtonClaims.Settled): String =
        "${reason.trimEnd('.')}; ${shade(shadeClosed)} claim=${claim.wire}"

    /** goal-control's disarm: whether it took back an armed refusal of the goal service's next start. */
    fun refuseOff(wasArmed: Boolean): String = if (wasArmed) "refuse=disarmed" else "refuse=none"

    private fun shade(closed: Boolean): String = if (closed) "shade=closed" else "shade=open"

    // "special-use+media-playback"; none without a type, as on Android 13, and without a service.
    private fun types(types: Set<ForegroundType>?): String =
        types?.takeIf { it.isNotEmpty() }?.sortedBy { it.ordinal }?.joinToString("+") { it.wire } ?: "none"
}
