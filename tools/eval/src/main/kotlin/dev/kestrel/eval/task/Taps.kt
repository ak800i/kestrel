package dev.kestrel.eval.task

import dev.kestrel.eval.hook.HookLost
import kotlin.time.Duration.Companion.seconds

/** One way to reach an element of a real app: its exact [label] or a part of one ([contains]), tapped or, with [long], long-pressed. */
data class TapCandidate(val label: String? = null, val contains: String? = null, val long: Boolean = false)

/**
 * The script's own taps in a real app (design D8 and D9 of sub-project 6), for what only the app can remove, such as a
 * test alarm. Each step lists candidates, tried in order. A candidate is tapped only while [packageName] is in front and
 * `screen-has` counts exactly one element that it names, so that a tap never picks one of several items, which may be
 * the owner's; `screen-tap -Package` checks the app in front again when it taps. A step whose candidates the screen does
 * not show is passed over when a later step's shows, as when the app opened the item already expanded. A candidate's
 * placeholders, such as "{time}", are filled from the run's values. The caller checks the result on the device.
 */
class AppTaps(private val scope: RunScope, private val packageName: String) {
    /** Runs [steps]; whether every step that was not passed over tapped. */
    fun run(steps: List<List<TapCandidate>>): Boolean {
        var at = 0
        while (at < steps.size) {
            val next = (at until steps.size).firstOrNull { index -> steps[index].any { shown(it) } } ?: return false
            if (!tap(steps[next])) return false
            at = next + 1
        }
        return true
    }

    private fun shown(candidate: TapCandidate): Boolean =
        scope.hooks.screenHas(packageName = packageName, label = candidate.label?.let { scope.values.fill(it) }, contains = candidate.contains?.let { scope.values.fill(it) })
            .int("count") == 1

    private fun tap(step: List<TapCandidate>): Boolean {
        val candidate = step.firstOrNull { shown(it) } ?: return false
        val tapped = try {
            scope.hooks.screenTap(candidate.label?.let { scope.values.fill(it) }, candidate.contains?.let { scope.values.fill(it) }, candidate.long, packageName)["tapped"] == "true"
        } catch (e: HookLost) {
            // The caller's check reads the result back.
            true
        }
        scope.clock.sleep(SETTLE)
        return tapped
    }

    private companion object {
        val SETTLE = 2.seconds
    }
}
