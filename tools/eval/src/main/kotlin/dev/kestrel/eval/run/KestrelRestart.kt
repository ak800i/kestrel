package dev.kestrel.eval.run

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceChanged
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.waitUntil
import dev.kestrel.eval.hook.Hooks
import dev.kestrel.eval.task.CrashGuardPassed
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Kestrel's process ended on purpose during a run (design 15.3 of sub-project 7: resume-after-death), through the hook
 * crash (spec 16.1). Android starts Kestrel again when it binds Kestrel's accessibility service again, so [restart] waits
 * for a new process and the service's binding, for at most [START] by the clock, closes Android's dialog about the crash
 * when it has the focus, and waits until Kestrel listens again ([listening]), as before a run. Kestrel's guard against a
 * crash loop (main spec 8.5) keeps it off after a crash soon after a restore turned it on, so a task that restarts Kestrel waits
 * out that guard before its run ([CrashGuardPassed]), and [restart] fails without a crash while voice-restore shows that
 * the guard holds; if Kestrel stays off all the same, the failure names the guard.
 */
class KestrelRestart(private val device: Device, private val hooks: Hooks, private val clock: Clock, private val listening: () -> Unit) {
    fun restart() {
        quietly { hooks.voiceRestore() }?.let { CrashGuardPassed.left(it, Duration.ZERO) }?.let { left ->
            error("Kestrel's guard against a crash loop holds for $left more, and a crash now would keep Kestrel off; the task's setup needs CrashGuardPassed")
        }
        val before = process()
        // The hook answers `crashing` and ends the process half a second later; a lost answer changes nothing, because
        // only a new process counts.
        quietly { hooks.crash(CRASH_TEXT) }
        // Each look reads through the wait's deadline, so a slow adb cannot stretch the wait; a look that failed is "not yet".
        val started = clock.waitUntil(START, POLL) { deadline ->
            val bounded = device.until(deadline)
            try {
                val now = process(bounded)
                now != null && now != before && bound(bounded)
            } catch (e: DeviceCommandFailed) {
                false
            }
        }
        check(started) { "Kestrel did not start again within ${START.inWholeSeconds} s after its process ended" }
        // The dialog stays until it is dismissed, so BACK goes to it only while it has the focus, never to the app below.
        if (crashDialogInFront()) device.idempotent("input keyevent KEYCODE_BACK")
        try {
            listening()
        } catch (e: Exception) {
            if (e is DeviceChanged) throw e
            throw IllegalStateException(
                "Kestrel did not listen after its process started again (${e.message ?: e.javaClass.simpleName}); its guard against a " +
                    "crash loop keeps it off after a crash soon after a restore turned it on (main spec 8.5)",
                e,
            )
        }
    }

    // Kestrel's process ID, or null while none runs; pidof fails without one, so the command ends with true.
    private fun process(on: Device = device): String? = on.read("pidof dev.kestrel || true").joinToString(" ").trim().ifEmpty { null }

    private fun bound(on: Device): Boolean = on.read("dumpsys activity services $SERVICE").any { "app=ProcessRecord" in it }

    private fun crashDialogInFront(): Boolean = device.read("dumpsys window").any { "mCurrentFocus" in it && CRASH_DIALOG in it }

    companion object {
        const val CRASH_TEXT = "plan 7G restart check"
        val START = 90.seconds
        private val POLL = 2.seconds
        private const val SERVICE = "dev.kestrel/.app.KestrelAccessibilityService"
        private const val CRASH_DIALOG = "Application Error: dev.kestrel"
    }
}
