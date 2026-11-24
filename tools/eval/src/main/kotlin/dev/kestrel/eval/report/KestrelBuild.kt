package dev.kestrel.eval.report

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.hook.HookAnswer
import kotlinx.serialization.Serializable

/**
 * The Kestrel build installed on a device, which a suite records in its `suite.json` (review I2 of plan 6B): its version
 * and the time of its install from `dumpsys package dev.kestrel`, which changes at every install, and the commit of the
 * worktree that the debug build was built from with whether that worktree had changes outside the tool and the
 * documentation ([dirty]), which the `state` hook answers; [commit] is null for a build that does not report it.
 */
@Serializable
data class KestrelBuild(
    val versionCode: Long? = null,
    val versionName: String? = null,
    val lastUpdateTime: String? = null,
    val commit: String? = null,
    val dirty: Boolean? = null,
) {
    /** Whether [other] is the same install of Kestrel: the same version, installed at the same time. */
    fun sameInstall(other: KestrelBuild?): Boolean =
        other != null && versionCode == other.versionCode && versionName == other.versionName && lastUpdateTime == other.lastUpdateTime

    /** The build as the console names it. */
    val label: String get() = "version $versionName ($versionCode), installed $lastUpdateTime, commit ${commit ?: "unknown"}${if (dirty == true) " with changes" else ""}"

    companion object {
        private const val KESTREL = "dev.kestrel"
        private val COMMIT = Regex("[0-9a-f]{40}")

        /** Kestrel's install on [device], from `dumpsys package`, or null when it is not installed. */
        fun installed(device: Device): KestrelBuild? = parse(device.read("dumpsys package $KESTREL"))

        /** The build that `dumpsys package dev.kestrel` shows in its package's own section, or null without one. */
        fun parse(lines: List<String>): KestrelBuild? {
            val start = lines.indexOfFirst { it.trim().startsWith("Package [$KESTREL]") }
            if (start < 0) return null
            val section = lines.drop(start + 1).takeWhile { !it.trim().startsWith("Package [") }
            fun field(name: String): String? = section.firstNotNullOfOrNull { line ->
                line.trim().split(' ').firstOrNull { it.startsWith("$name=") }?.substringAfter('=')
            }
            val time = section.firstOrNull { it.trim().startsWith("lastUpdateTime=") }?.trim()?.substringAfter('=')
            val code = field("versionCode")?.toLongOrNull() ?: return null
            return KestrelBuild(versionCode = code, versionName = field("versionName"), lastUpdateTime = time)
        }

        /** [install] with the commit and the dirty flag that the `state` hook answered, when they are well formed. */
        fun withState(install: KestrelBuild, state: HookAnswer): KestrelBuild {
            val commit = state["commit"]?.takeIf { COMMIT.matches(it) } ?: return install
            val dirty = when (state["dirty"]) {
                "0" -> false
                "1" -> true
                else -> return install
            }
            return install.copy(commit = commit, dirty = dirty)
        }
    }
}
