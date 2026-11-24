package dev.kestrel.eval.run

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.Refusal

/**
 * The owner's permission set for the evaluation (his decision of 2026-11-24; plan 6B-basefix): Kestrel's data-tool
 * permissions of its manifest, granted before every live series on both test devices and kept granted afterwards, so that
 * no task stops at Kestrel's own permission request, which a run would cancel, and both devices test the same Kestrel.
 * Location, phone state, call answering and the partial media access (READ_MEDIA_VISUAL_USER_SELECTED, which full access
 * makes needless) are not in the set and stay as they are. Nothing takes one of them back, by that decision and because
 * Android ends Kestrel's process when one of its permissions is taken back; AGENTS.md says so. A task that needs some of
 * them declares them with [dev.kestrel.eval.task.KeptPermissions], which grants them through [keep] in the same way.
 */
object EvalPermissions {
    const val KESTREL = "dev.kestrel"

    val SET = listOf(
        "android.permission.READ_CALENDAR",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
    )

    /** Grants the whole [SET] through [keep] and returns each one's state; throws [Refusal], before any run, when one is still not granted. */
    fun grant(device: Device): Map<String, Boolean> {
        val states = keep(device, SET)
        val missing = states.filterValues { !it }.keys
        if (missing.isNotEmpty()) throw Refusal("The live series does not run: ${lacks(device, missing)}")
        return states
    }

    /**
     * Grants each of [permissions], all of them in [SET], that `dumpsys package` does not show as granted, through the
     * device guard, reads them back and returns each one's state. It takes none of them back, now or later.
     */
    fun keep(device: Device, permissions: List<String>): Map<String, Boolean> {
        require(permissions.all { it in SET }) { "Only the evaluation's permission set is kept: ${permissions - SET.toSet()} is not in it." }
        val before = granted(device, permissions)
        for (permission in permissions.filter { it !in before }) {
            try {
                device.idempotent("pm grant $KESTREL $permission")
            } catch (e: DeviceCommandFailed) {
                // The read-back below names it.
            }
        }
        val after = granted(device, permissions)
        return permissions.associateWith { it in after }
    }

    /**
     * What to do when [missing] stayed ungranted after `pm grant`. Android leaves a hard-restricted permission (SMS, call
     * log) ungranted, without an error, when the app was installed without the installer's exemption, and Settings does
     * not offer it then either; `adb install` gives that exemption.
     */
    fun lacks(device: Device, missing: Collection<String>): String =
        "Kestrel on ${device.serial} still lacks ${missing.joinToString()} after pm grant. Install Kestrel with scripts\\install.ps1, " +
            "which uses adb install, so that Android exempts its hard-restricted permissions (SMS, call log), and keeps its data; then run again."

    private fun granted(device: Device, permissions: List<String>): List<String> =
        Dumps.grantedPermissions(device.read("dumpsys package $KESTREL"), permissions)
}