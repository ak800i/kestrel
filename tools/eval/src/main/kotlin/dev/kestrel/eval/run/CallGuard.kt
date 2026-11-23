package dev.kestrel.eval.run

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.Dumps
import kotlin.time.Duration.Companion.milliseconds

/**
 * The call state before and after each run (design D5 of sub-project 6; task-check.ps1's `Test-RunCall` with
 * `Stop-DeviceCall`): a call is ended at once with the end-call key, never answered or confirmed (process rule 10), and
 * the run fails. A call state that cannot be read counts as no call, with a warning.
 */
class CallGuard(private val device: Device, private val console: Console, private val clock: Clock) {
    /** Whether a call showed; it is ended before this returns. */
    fun check(): Boolean {
        val registry = registry() ?: run {
            console.warn("The call state of ${device.serial} could not be read.")
            return false
        }
        if (!Dumps.callActive(registry)) return false
        // The key ends an ordinary call through Android; it is sent only while a call shows, because without one it turns
        // the screen off. A lost answer is read back below.
        device.once("input keyevent KEYCODE_ENDCALL")
        repeat(POLLS) {
            clock.sleep(POLL)
            val now = registry()
            if (now != null && !Dumps.callActive(now)) return true
        }
        console.warn("A call is still active on ${device.serial}. End it on the phone app's screen.")
        return true
    }

    private fun registry(): List<String>? = try {
        device.read("dumpsys telephony.registry")
    } catch (e: DeviceCommandFailed) {
        null
    }

    private companion object {
        const val POLLS = 20
        val POLL = 500.milliseconds
    }
}
