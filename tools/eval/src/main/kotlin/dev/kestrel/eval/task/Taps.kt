package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookLost
import kotlin.time.Duration.Companion.seconds

/**
 * One way to reach an element of a real app: its exact [label] or a part of one ([contains]), tapped or, with [long],
 * long-pressed. With [className] or [state] (screen-has' states, such as "clickable"), the candidate names the one element
 * of that class or state among those that the text names, such as Gmail's clickable row among its own subject and
 * snippet, or the clickable tab counter around its own inner copy; with [first] too, it names the first of several such
 * elements, all of which the caller knows to belong to one control, such as Samsung Clock's clickable Delete container
 * around its clickable Delete button.
 */
data class TapCandidate(
    val label: String? = null,
    val contains: String? = null,
    val long: Boolean = false,
    val className: String? = null,
    val state: String? = null,
    val first: Boolean = false,
)

/**
 * The script's own taps in a real app (design D8 and D9 of sub-project 6), for what only the app can remove, such as a
 * test e-mail in Gmail or the A71's test timer. Each step lists candidates, tried in order. A candidate is tapped only
 * while [packageName] is in front and `screen-has` counts exactly one element that it names, so that a tap never picks
 * one of several items, which may be the owner's; `screen-tap -Package` checks the app in front again when it taps, and
 * its answer must count exactly one element too. A candidate with a class or a state counts exactly one element of them
 * (at least one with [TapCandidate.first]), and is tapped only when that element is the first that its text names, where
 * screen-tap taps, and when screen-tap's answer counts as many elements as `screen-has` counted for the text. A step that
 * the screen does not show ends the taps, and so do a tap that counted another number and a lost answer, which gives no
 * count: a later step, such as a lone Delete, is never tapped for an item that an earlier step did not select. A
 * candidate's placeholders, such as "{time}", are filled from the run's values. The caller checks the result on the
 * device.
 */
class AppTaps(private val scope: RunScope, private val packageName: String) {
    /** Runs [steps] in their order; whether each of them tapped one element. */
    fun run(steps: List<List<TapCandidate>>): Boolean = steps.all { tap(it) }

    /** The count that screen-tap's answer must give for [candidate], or null when the screen does not show it once. */
    private fun shown(candidate: TapCandidate): Int? {
        val label = candidate.label?.let { scope.values.fill(it) }
        val contains = candidate.contains?.let { scope.values.fill(it) }
        val named = scope.hooks.screenHas(packageName = packageName, label = label, contains = contains)
        if (candidate.className == null && candidate.state == null) return 1.takeIf { named.int("count") == 1 }
        val narrowed = scope.hooks.screenHas(packageName = packageName, label = label, contains = contains, className = candidate.className, state = candidate.state)
        if ((if (candidate.first) narrowed.int("count") < 1 else narrowed.int("count") != 1) || named.int("count") < 1) return null
        return named.int("count").takeIf { named["bounds"].let { it != null && it != "none" && it == narrowed["bounds"] } }
    }

    private fun tap(step: List<TapCandidate>): Boolean {
        var expected: Int? = null
        val candidate = step.firstOrNull { candidate -> shown(candidate)?.also { expected = it } != null } ?: return false
        val answer = try {
            scope.hooks.screenTap(candidate.label?.let { scope.values.fill(it) }, candidate.contains?.let { scope.values.fill(it) }, candidate.long, packageName)
        } catch (e: HookLost) {
            // The caller's check reads the result back.
            return false
        }
        scope.clock.sleep(SETTLE)
        return answer["tapped"] == "true" && answer.int("count") == expected
    }

    private companion object {
        val SETTLE = 2.seconds
    }
}

/**
 * One UI's one-time tip over an app that uses the full screen ("This app is using the full screen. …"), SystemUI's
 * window ImmersiveModeConfirmation, which takes the focus and can cover the screen, as over Samsung Clock's ringing
 * timer on the A71. It is dismissed with its own button, One UI's "OK" (AOSP's "Got it"), only while that window has the
 * focus and `screen-has` counts the button exactly once.
 */
object ImmersiveTip {
    const val WINDOW = "ImmersiveModeConfirmation"
    val BUTTONS = listOf("OK", "Got it")

    /** Whether the tip has the focus now. */
    fun shown(scope: RunScope): Boolean = scope.device.read("dumpsys window").any { it.contains("mCurrentFocus=") && it.contains(WINDOW) }

    /** Dismisses the tip when it has the focus; whether it no longer has it. */
    fun dismiss(scope: RunScope): Boolean {
        if (!shown(scope)) return true
        val button = BUTTONS.firstOrNull { scope.hooks.screenHas(label = it).int("count") == 1 } ?: return false
        try {
            scope.hooks.screenTap(button)
        } catch (e: HookLost) {
            // Read back below.
        }
        scope.clock.sleep(1.seconds)
        return !shown(scope)
    }
}
