package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.run.EvalAccess
import dev.kestrel.eval.run.EvalPermissions
import java.util.Collections
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

/**
 * An app's first-run question in Android's permission dialog, such as YouTube's "Allow YouTube to send you
 * notifications?" or Maps' "Allow Maps to access this device's location?", dismissed before the run without an answer:
 * the first installed and enabled app of [packages] opens [uri], and while the permission dialog is in front, Back
 * dismisses it, which grants nothing and leaves the state of [permissions] as it was (checked in `dumpsys package`). The
 * app's activity stays behind the home screen, so that the task's own link reaches it without a new question: these apps
 * ask again each time that their main activity is created. When Back leaves the app's activity closed, the step tries
 * once more. A dialog that Back does not close, or an app that never stays open behind it, makes the task untestable on
 * the device, because only the owner may answer the question; a state that changed fails the setup. The app is stopped
 * after the run.
 */
data class DismissPermissionPrompt(val packages: List<String>, val permissions: List<String>, val uri: String) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val app = packages.firstOrNull { Installed.enabled(scope, it) } ?: return SetupResult.Untestable("${packages.joinToString(" or ")} is not installed")
        val before = state(scope, app)
        scope.restores.push("$app stopped", "Close $app.") { scope.device.idempotent("am force-stop $app") }
        val open = (1..TRIES).any { open(scope, app) }
        scope.restores.change("the home screen in front", "Press Home.") { scope.device.idempotent("input keyevent KEYCODE_HOME") }
        if (state(scope, app) != before) throw SetupFailed("The state of ${permissions.joinToString()} for $app changed when its question was dismissed; check it in Settings > Apps.")
        if (!open) return SetupResult.Untestable("Android asks whether $app may have ${permissions.joinToString(" or ")}, which only the owner may answer")
        return SetupResult.Ready
    }

    /** Opens [uri] in [app] and dismisses the question; whether the app's activity is in front afterwards. */
    private fun open(scope: RunScope, app: String): Boolean {
        scope.restores.change("$app opened", "Close $app.") { scope.device.idempotent("am start -W -a android.intent.action.VIEW -d '$uri' -p $app") }
        val asked = (1..WAIT).any {
            scope.clock.sleep(1.seconds)
            front(scope) in CONTROLLERS
        }
        if (asked) {
            // A lost answer is read back below.
            scope.restores.change("Android's permission dialog dismissed", "Close $app.") { scope.device.once("input keyevent KEYCODE_BACK") }
            scope.clock.sleep(2.seconds)
        }
        return front(scope) == app
    }

    private fun front(scope: RunScope): String? = Dumps.resumedPackage(scope.device.read("dumpsys activity activities"))

    /** The permissions' lines of `dumpsys package`: whether each is granted and its flags, which say whether the owner answered. */
    private fun state(scope: RunScope, app: String): String =
        scope.device.read("dumpsys package $app").map { it.trim() }.filter { line -> permissions.any { line.startsWith("$it:") } }.joinToString("|")

    companion object {
        val NOTIFICATIONS = listOf("android.permission.POST_NOTIFICATIONS")
        val LOCATION = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        private const val WAIT = 8
        private const val TRIES = 2
        private val CONTROLLERS = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
    }
}

/** Opens [uri] in [packageName], a rehearsal's end state such as a page in Firefox, and waits [settle]; the app is stopped afterwards. */
data class OpenUri(val uri: String, val packageName: String, val settle: Duration = 5.seconds) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        scope.restores.push("$packageName stopped", "Close $packageName.") { scope.device.idempotent("am force-stop $packageName") }
        // A lost answer needs no read-back here: the task's checks read the screen next.
        scope.restores.change("a page opened in $packageName", "Close $packageName.") {
            scope.device.once("am start -W -a android.intent.action.VIEW -d '${scope.values.fill(uri)}' -p $packageName")
        }
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
 * Grants a test app, [packageName], the runtime [permissions] that it lacks, and takes back after the run only those that
 * the run granted: a grant that succeeded, or one that failed but that the grant state read after it shows (the answer
 * of a grant that took effect may be lost). A grant that fails, such as of a permission that the app does not request,
 * fails the run's setup without a restore that would fail too. Taking a permission back ends the app's process, so
 * Kestrel's own permissions are never granted here: they are the evaluation's kept set, which [KeptPermissions] grants.
 */
data class Grant(val packageName: String, val permissions: List<String>) : Setup {
    init {
        require(packageName != EvalPermissions.KESTREL) { "Kestrel's permissions are the evaluation's kept set: declare them with KeptPermissions." }
    }

    override fun apply(scope: RunScope): SetupResult {
        val granted = granted(scope)
        val missing = permissions.filter { it !in granted }
        if (missing.isEmpty()) return SetupResult.Ready
        val byHand = "Settings > Apps > $packageName > Permissions: take back ${missing.joinToString()}."
        // Written inside each change, which the shutdown hook awaits before it runs the restore.
        val byRun = Collections.synchronizedSet(LinkedHashSet<String>())
        scope.restores.push("the permissions that the run granted $packageName", byHand) {
            val failed = synchronized(byRun) { byRun.toList() }.filter { runCatching { scope.device.idempotent("pm revoke $packageName $it") }.isFailure }
            check(failed.isEmpty()) { "Could not take back ${failed.joinToString()}." }
        }
        for (permission in missing) {
            scope.restores.change("$permission granted to $packageName", byHand) {
                try {
                    scope.device.idempotent("pm grant $packageName $permission")
                } catch (e: Exception) {
                    if (runCatching { permission in granted(scope) }.getOrDefault(true)) byRun.add(permission)
                    throw e
                }
                byRun.add(permission)
            }
        }
        return SetupResult.Ready
    }

    override fun probe(scope: RunScope): Map<String, String> = mapOf("granted:$packageName" to granted(scope).sorted().joinToString(","))

    private fun granted(scope: RunScope): List<String> = Dumps.grantedPermissions(scope.device.read("dumpsys package $packageName"), permissions)
}

/**
 * The accesses of Kestrel that a task needs, all of them in the evaluation's kept set ([EvalPermissions.SET], the
 * owner's decisions of 2026-11-24): its runtime permissions and its notification access. The ones that Kestrel lacks are
 * given and kept, with no restore, because the set stays on both test devices and taking one back would end Kestrel's
 * process. A live series has given the whole set before its first run, so this step then only reads it; a dry series or
 * a single run gives what its task needs. One that is still missing fails the run's setup. A task that needs a state of
 * Kestrel's accesses other than the kept set would need a step of its own that says so and restores the kept set; no
 * task does.
 */
data class KeptPermissions(val accesses: List<EvalAccess>) : Setup {
    init {
        require(accesses.isNotEmpty()) { "A task's KeptPermissions names at least one access of the kept set." }
    }

    override fun apply(scope: RunScope): SetupResult {
        val missing = EvalPermissions.keep(scope.device, scope.clock, accesses).filterValues { !it }.keys
        check(missing.isEmpty()) { EvalPermissions.lacks(scope.device, missing) }
        return SetupResult.Ready
    }

    companion object {
        /** Photos and videos with full access, which makes the partial access (READ_MEDIA_VISUAL_USER_SELECTED) needless. */
        val MEDIA = KeptPermissions(listOf(EvalAccess.READ_MEDIA_IMAGES, EvalAccess.READ_MEDIA_VIDEO))

        /** The notification access that list_notifications needs. */
        val NOTIFICATIONS = KeptPermissions(listOf(EvalAccess.NOTIFICATION_LISTENER))
    }
}
