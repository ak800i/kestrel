package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookLost
import kotlin.time.Duration.Companion.seconds

/** One way to reach an element of a real app: its exact [label] or a part of one ([contains]), tapped or, with [long], long-pressed. */
data class TapCandidate(val label: String? = null, val contains: String? = null, val long: Boolean = false)

/**
 * The script's own taps in a real app (design D8 and D9 of sub-project 6), for what only the app can remove, such as a
 * test alarm. Each step lists candidates, tried in order. A candidate is tapped only while [packageName] is in front and
 * `screen-has` counts exactly one element that it names, so that a tap never picks one of several items, which may be
 * the owner's; `screen-tap -Package` checks the app in front again when it taps, and its answer must count exactly one
 * element too. A step that the screen does not show ends the taps, and so do a tap that counted another number and a
 * lost answer, which gives no count: a later step, such as a lone Delete, is never tapped for an item that an earlier
 * step did not select. A candidate's placeholders, such as "{time}", are filled from the run's values. The caller checks
 * the result on the device.
 */
class AppTaps(private val scope: RunScope, private val packageName: String) {
    /** Runs [steps] in their order; whether each of them tapped one element. */
    fun run(steps: List<List<TapCandidate>>): Boolean = steps.all { tap(it) }

    private fun shown(candidate: TapCandidate): Boolean =
        scope.hooks.screenHas(packageName = packageName, label = candidate.label?.let { scope.values.fill(it) }, contains = candidate.contains?.let { scope.values.fill(it) })
            .int("count") == 1

    private fun tap(step: List<TapCandidate>): Boolean {
        val candidate = step.firstOrNull { shown(it) } ?: return false
        val answer = try {
            scope.hooks.screenTap(candidate.label?.let { scope.values.fill(it) }, candidate.contains?.let { scope.values.fill(it) }, candidate.long, packageName)
        } catch (e: HookLost) {
            // The caller's check reads the result back.
            return false
        }
        scope.clock.sleep(SETTLE)
        return answer["tapped"] == "true" && answer.int("count") == 1
    }

    private companion object {
        val SETTLE = 2.seconds
    }
}
