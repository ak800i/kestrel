package dev.kestrel.eval.run

import dev.kestrel.eval.device.Clock
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.device.Refusal
import kotlin.time.Duration.Companion.seconds

/**
 * The owner's kept set for the evaluation (his decisions of 2026-11-24; plan 6B-basefix): Kestrel's data-tool permissions
 * of its manifest and its notification access ([EvalAccess]), given before every live series on both test devices and
 * kept afterwards, so that no task stops at Kestrel's own permission request or its notification-access hand-over, which
 * a run would cancel, and both devices test the same Kestrel. Location, phone state, call answering and the partial media
 * access (READ_MEDIA_VISUAL_USER_SELECTED, which full access makes needless) are not in the set and stay as they are.
 * Nothing takes one of the set back, by those decisions and because Android ends Kestrel's process when one of its
 * permissions or its notification access is taken back; AGENTS.md says so. A task that needs some of them declares them
 * with [dev.kestrel.eval.task.KeptPermissions], which gives them through [keep] in the same way.
 */
object EvalPermissions {
    const val KESTREL = "dev.kestrel"

    val SET: List<EvalAccess> = EvalAccess.entries

    /**
     * Gives the whole [SET] through [keep] and returns each one's state by its [EvalAccess.id], for `suite.json`; throws
     * [Refusal], before any run, when one is still missing.
     */
    fun grant(device: Device, clock: Clock): Map<String, Boolean> {
        val states = keep(device, clock, SET)
        val missing = states.filterValues { !it }.keys
        if (missing.isNotEmpty()) throw Refusal("The live series does not run: ${lacks(device, missing)}")
        return states.mapKeys { it.key.id }
    }

    /**
     * Gives each of [accesses] that the device does not show as given, through the device guard, reads them back and
     * returns each one's state. It takes none of them back, now or later.
     */
    fun keep(device: Device, clock: Clock, accesses: List<EvalAccess>): Map<EvalAccess, Boolean> {
        val states = accesses.groupBy { it.kind }.flatMap { (kind, group) ->
            when (kind) {
                EvalAccess.Kind.RUNTIME_PERMISSION -> grantPermissions(device, group).toList()
                EvalAccess.Kind.NOTIFICATION_LISTENER -> group.map { it to allowListener(device, clock, it) }
            }
        }.toMap()
        return accesses.associateWith { states.getValue(it) }
    }

    /**
     * What to do when [missing] stayed missing after it was given. Android leaves a hard-restricted permission (SMS, call
     * log) ungranted, without an error, when the app was installed without the installer's exemption, and Settings does not
     * offer it then either; `adb install` gives that exemption. Android may also restrict Kestrel's notification access;
     * the owner then allows it in Kestrel's App info.
     */
    fun lacks(device: Device, missing: Collection<EvalAccess>): String {
        val permissions = missing.filter { it.kind == EvalAccess.Kind.RUNTIME_PERMISSION }
        val reasons = buildList {
            if (permissions.isNotEmpty()) {
                add(
                    "Kestrel on ${device.serial} still lacks ${permissions.joinToString { it.id }} after pm grant. Install Kestrel with scripts\\install.ps1, " +
                        "which uses adb install, so that Android exempts its hard-restricted permissions (SMS, call log), and keeps its data; then run again.",
                )
            }
            if (EvalAccess.NOTIFICATION_LISTENER in missing) {
                add(
                    "Kestrel on ${device.serial} still lacks its notification access after cmd notification allow_listener. Allow restricted settings in " +
                        "Kestrel's App info if Android asks, turn Kestrel on in Settings > Notifications > Device and app notifications (or Notification access), " +
                        "and keep it on; then run again.",
                )
            }
        }
        return reasons.joinToString(" ")
    }

    private fun grantPermissions(device: Device, permissions: List<EvalAccess>): Map<EvalAccess, Boolean> {
        val before = granted(device, permissions)
        for (permission in permissions.filter { it !in before }) {
            try {
                device.idempotent("pm grant $KESTREL ${permission.id}")
            } catch (e: DeviceCommandFailed) {
                // The read-back below names it.
            }
        }
        val after = granted(device, permissions)
        return permissions.associateWith { it in after }
    }

    private fun granted(device: Device, permissions: List<EvalAccess>): List<EvalAccess> {
        val shown = Dumps.grantedPermissions(device.read("dumpsys package $KESTREL"), permissions.map { it.id })
        return permissions.filter { it.id in shown }
    }

    /** Allows the notification listener [access] when the setting does not name it, and waits up to [LISTENER_READS] s until it does. */
    private fun allowListener(device: Device, clock: Clock, access: EvalAccess): Boolean {
        if (listening(device, access)) return true
        try {
            device.idempotent("cmd notification allow_listener ${access.id}")
        } catch (e: DeviceCommandFailed) {
            // The read-back below names it.
        }
        repeat(LISTENER_READS) {
            clock.sleep(1.seconds)
            if (listening(device, access)) return true
        }
        return false
    }

    // The setting lists the allowed listeners joined with ':', each as a component that may have its short form.
    private fun listening(device: Device, access: EvalAccess): Boolean {
        val short = access.id.replace("/${KESTREL}.", "/.")
        return device.read("settings get secure enabled_notification_listeners").joinToString("").trim().split(':').any { it == access.id || it == short }
    }

    private const val LISTENER_READS = 10
}
