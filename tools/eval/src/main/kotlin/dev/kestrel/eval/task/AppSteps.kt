package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookLost
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Stops an app before the run, and again after it, so that the task starts it fresh and the device is left without it. */
data class ForceStop(val packageName: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("$packageName stopped", "Close $packageName.") { scope.device.idempotent("am force-stop $packageName") }
        scope.device.idempotent("am force-stop $packageName")
        return SetupResult.Ready
    }
}

/** Opens [uri] in [packageName], a rehearsal's end state such as a page in Firefox, and waits [settle]; the app is stopped afterwards. */
data class OpenUri(val uri: String, val packageName: String, val settle: Duration = 5.seconds) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("$packageName stopped", "Close $packageName.") { scope.device.idempotent("am force-stop $packageName") }
        // A lost answer needs no read-back here: the task's checks read the screen next.
        scope.device.once("am start -W -a android.intent.action.VIEW -d '${scope.values.fill(uri)}' -p $packageName")
        scope.clock.sleep(settle)
        return SetupResult.Ready
    }
}

/** The script's own tap on the element labelled [label] (hook screen-tap), a rehearsal's end state; a lost answer is read back by the checks. */
data class ScreenTap(val label: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val answer = try {
            scope.hooks.screenTap(label)
        } catch (e: HookLost) {
            return SetupResult.Ready
        }
        if (answer["tapped"] != "true") throw SetupFailed("The element \"$label\" was not tapped (${answer["error"] ?: "count=${answer["count"]}"}).")
        scope.clock.sleep(SETTLE)
        return SetupResult.Ready
    }

    private companion object {
        val SETTLE = 2.seconds
    }
}

/**
 * Grants [packageName] the runtime [permissions] that it lacks, and takes back only those after the run, as task-check.ps1
 * did with Kestrel's media permissions. Android 13 does not know READ_MEDIA_VISUAL_USER_SELECTED, which exists from API 34.
 * Taking a permission back ends the app's process; the runner waits for Kestrel to listen again before the next run.
 */
data class Grant(val packageName: String, val permissions: List<String>) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val granted = granted(scope)
        val missing = permissions.filter { it !in granted && (scope.device.test.sdk >= 34 || it != VISUAL_USER_SELECTED) }
        if (missing.isEmpty()) return SetupResult.Ready
        scope.restores.push("the permissions that the run granted $packageName", "Settings > Apps > $packageName > Permissions: take back ${missing.joinToString()}.") {
            for (permission in missing) scope.device.idempotent("pm revoke $packageName $permission")
        }
        for (permission in missing) scope.device.idempotent("pm grant $packageName $permission")
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("granted:$packageName" to granted(scope).sorted().joinToString(","))

    private fun granted(scope: RunScope): List<String> = Dumps.grantedPermissions(scope.device.read("dumpsys package $packageName"), permissions)

    companion object {
        const val VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        val MEDIA = listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", VISUAL_USER_SELECTED)
    }
}
