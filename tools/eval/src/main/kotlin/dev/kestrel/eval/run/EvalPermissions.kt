package dev.kestrel.eval.run

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.Refusal

/**
 * The owner's permission set for the evaluation (his decision of 2026-11-24; plan 6B-basefix): Kestrel's data-tool
 * permissions of its manifest, granted before every live series on both test devices and kept granted afterwards, so that
 * no task stops at Kestrel's own permission request, which a run would cancel, and both devices test the same Kestrel.
 * Location, phone state and call answering are not in the set and stay as they are. There is no restore, by that
 * decision; AGENTS.md says so.
 */
object EvalPermissions {
    private const val KESTREL = "dev.kestrel"

    val SET = listOf(
        "android.permission.READ_CALENDAR",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
    )

    /**
     * Grants each permission of [SET] that `dumpsys package` does not show as granted, through the device guard, reads
     * the set back and returns each one's state; throws [Refusal], before any run, when one is still not granted.
     */
    fun grant(device: Device): Map<String, Boolean> {
        val before = granted(device)
        for (permission in SET.filter { it !in before }) {
            try {
                device.idempotent("pm grant $KESTREL $permission")
            } catch (e: DeviceCommandFailed) {
                // The read-back below names it.
            }
        }
        val after = granted(device)
        val states = SET.associateWith { it in after }
        val missing = states.filterValues { !it }.keys
        if (missing.isNotEmpty()) {
            throw Refusal("Kestrel on ${device.serial} still lacks ${missing.joinToString()} after pm grant, so the live series does not run; grant it by hand in Settings > Apps > Kestrel > Permissions.")
        }
        return states
    }

    private fun granted(device: Device): List<String> = Dumps.grantedPermissions(device.read("dumpsys package $KESTREL"), SET)
}
