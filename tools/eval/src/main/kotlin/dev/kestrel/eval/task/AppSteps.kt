package dev.kestrel.eval.task

import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookLost
import dev.kestrel.eval.hook.HookSilent
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
 * Grants [packageName] the runtime [permissions] that it lacks, and takes back after the run only those that the run
 * granted, as task-check.ps1 did with Kestrel's media permissions: a grant that succeeded, or one that failed but that
 * the grant state read after it shows (the answer of a grant that took effect may be lost). A grant that fails, such as
 * of a permission that the app does not request, fails the run's setup without a restore that would fail too. Android 13
 * does not know READ_MEDIA_VISUAL_USER_SELECTED, which exists from API 34. Taking a permission back ends the app's
 * process; Kestrel's may come back with listening off, so [Listening] turns it on again after the restore, and the runner
 * also waits for Kestrel to listen before the next run.
 */
data class Grant(val packageName: String, val permissions: List<String>) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val granted = granted(scope)
        val missing = permissions.filter { it !in granted && (scope.device.test.sdk >= 34 || it != VISUAL_USER_SELECTED) }
        if (missing.isEmpty()) return SetupResult.Ready
        val byHand = "Settings > Apps > $packageName > Permissions: take back ${missing.joinToString()}."
        // Taking back one of Kestrel's permissions ends its process.
        if (packageName == Listening.KESTREL) Listening.keep(scope)
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

    companion object {
        const val VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        val MEDIA = listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO", VISUAL_USER_SELECTED)
    }
}

/**
 * Kestrel's listening, which a restore that ends Kestrel's process can leave off: taking back one of its permissions or
 * its notification access ends the process, and the process does not always come back listening. [keep], called before
 * such a change, pushes a restore that runs after the change's own restore and turns listening on again, when Kestrel
 * listened before the change.
 */
object Listening {
    const val KESTREL = "dev.kestrel"
    private const val QUIET_READS = 3
    private const val LISTENING_READS = 2
    private val BACK = 90.seconds

    /** Pushes the restore when Kestrel listens now; it changes nothing on the device. */
    fun keep(scope: RunScope) {
        if (mode(scope) != "listening") return
        scope.restores.push("Kestrel listening again", "Run: scripts\\hook.ps1 -Serial ${scope.device.serial} voice -Send toggle") { again(scope) }
    }

    /** Kestrel's listening mode (voice-state), or null while its process does not answer. */
    fun mode(scope: RunScope): String? = try {
        scope.hooks.voiceState()["mode"]
    } catch (e: HookSilent) {
        null
    } catch (e: HookError) {
        null
    }

    /**
     * Waits up to [BACK] until Kestrel listens at [LISTENING_READS] reads in a row, 3 s apart, the first 3 s after the
     * change's restore, so that a process that ends a moment later is not taken for one that listens. When its process
     * answers and does not listen at [QUIET_READS] reads in a row, one toggle turns listening on; a lost answer of the
     * toggle is read back by the next reads. The toggle comes with a warning, because the spec has Kestrel return to the
     * mode that it had after Android ends its process, so that a defect of Kestrel stays visible in every run.
     */
    private fun again(scope: RunScope) {
        val deadline = scope.clock.elapsed() + BACK
        var quiet = 0
        var listening = 0
        var toggled = false
        while (true) {
            scope.clock.sleep(3.seconds)
            val mode = mode(scope)
            listening = if (mode == "listening") listening + 1 else 0
            if (listening >= LISTENING_READS) return
            quiet = if (mode == "off" || mode == "paused") quiet + 1 else 0
            if (!toggled && quiet >= QUIET_READS) {
                toggled = true
                scope.console.warn(
                    "Kestrel did not return to listening after its process restarted, as the spec says that it should; the run turns listening back on. " +
                        "This may be a defect of Kestrel.",
                )
                try {
                    scope.hooks.voiceSend("toggle")
                } catch (e: HookLost) {
                    // Read back by the next reads.
                }
            }
            check(scope.clock.elapsed() < deadline) { "Kestrel does not listen again after its process ended." }
        }
    }
}
