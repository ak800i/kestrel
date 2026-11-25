package dev.kestrel.app.debug

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import dev.kestrel.app.AppGraph
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.debug.spike.SpikeHooks
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.ScreenJson
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.overlay.NumbersMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/** A test hook's answer, which becomes the broadcast's result code and data. */
internal data class HookReply(val code: Int, val text: String) {
    companion object {
        /** The result code of a command that failed. A command that never ran keeps the initial code, 0. */
        const val FAILED = 1

        fun ok(text: String) = HookReply(Activity.RESULT_OK, text)

        fun failed(message: String) = HookReply(FAILED, "error: $message")
    }
}

/**
 * Runs the test-hook commands (design spec, section 16.1). The intent's action is [ACTION_PREFIX] followed by the
 * command, and its extras are the arguments; `scripts/hook.ps1` lists them.
 */
internal class TestHooks(private val graph: AppGraph, private val context: Context) {
    private val cacheDir: File get() = context.cacheDir

    suspend fun run(intent: Intent): HookReply = try {
        HookReply.ok(answer(intent))
    } catch (error: HookError) {
        HookReply.failed(error.message)
    }

    private suspend fun answer(intent: Intent): String {
        val command = intent.action.orEmpty().removePrefix(ACTION_PREFIX)
        if (command == "STATE") return state()
        if (command == "TEST_MODE") return testMode(intent)
        if (command == "CRASH") return crash(intent.requiredText())
        if (command in SpikeHooks.COMMANDS) return SpikeHooks(context, graph).run(command, intent)
        if (command in VoiceHooks.COMMANDS) return VoiceHooks(graph).run(command, intent)
        if (command in ExpectHooks.COMMANDS) return ExpectHooks(graph).run(command, intent)
        if (command in ScreenHooks.COMMANDS) return ScreenHooks(graph).run(command, intent)
        if (command in EvalHooks.COMMANDS) return EvalHooks(graph, context).run(command, intent)
        if (command in SessionHooks.COMMANDS) return SessionHooks(graph, context).run(command, intent)
        if (command in ProviderHooks.COMMANDS) return ProviderHooks(graph).run(command, intent)
        if (command in AgentHooks.COMMANDS) return AgentHooks(graph, context).run(command, intent)
        if (command in GoalServiceHooks.COMMANDS) return GoalServiceHooks(graph, context).run(command, intent)
        if (command in ConversationHooks.COMMANDS) return ConversationHooks(graph).run(command, intent)
        if (command == "PROFILES") return profiles()
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val actions = session.actions
        return when (command) {
            "SNAPSHOT" -> save(ScreenJson.encode(session.snapshots.current().snapshot))
            "TAP" -> done(tap(session, intent))
            "TYPE" -> done(actions.setText(target(session, intent), intent.requiredText()))
            "SCROLL" -> done(actions.scroll(intent.choice<ScrollDirection>(EXTRA_DIRECTION), optionalTarget(session, intent)))
            "GLOBAL" -> done(actions.global(intent.choice<GlobalAction>(EXTRA_ACTION)))
            "OVERLAY" -> {
                overlay(session, intent)
                state()
            }
            // How many snapshots the screen layer built and how long they took, for the A71 session (design spec, 8.3).
            "SNAPSHOTS" -> "builds=${session.snapshotBuilds} millis=${session.snapshotMillis}"
            else -> throw HookError("unknown command '$command'")
        }
    }

    /**
     * The profiles that the launcher API lists, for the A71 session of sub-project 3 (research r21): each one's serial
     * number, its number of launcher apps and, from Android 15, its user type. It never names a user or an app.
     */
    private fun profiles(): String {
        val launcher = context.getSystemService(LauncherApps::class.java) ?: throw HookError("the launcher API is missing")
        val users = context.getSystemService(UserManager::class.java)
        return launcher.profiles.joinToString("; ") { user ->
            val apps = try {
                launcher.getActivityList(null, user).size.toString()
            } catch (e: SecurityException) {
                "refused"
            }
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                launcher.getLauncherUserInfo(user)?.userType
            } else {
                null
            }
            "user=${users?.getSerialNumberForUser(user) ?: "unknown"} apps=$apps type=${type ?: "unknown"}"
        }
    }

    /**
     * Writes a snapshot to Kestrel's cache and answers with the file's path in Kestrel's data folder, because a
     * broadcast's answer must stay under Android's limit of about 1 MB. `scripts/hook.ps1` reads the file with
     * `run-as` and deletes it.
     */
    private suspend fun save(json: String): String = withContext(Dispatchers.IO) {
        File(cacheDir, SNAPSHOT_FILE).writeText(json)
        "${cacheDir.name}/$SNAPSHOT_FILE"
    }

    /**
     * Ends Kestrel's process with an error whose message is [text], thrown on the main thread a moment after the answer,
     * so that a check can look for [text] in Android's crash records (design spec, section 13.5).
     */
    private fun crash(text: String): String {
        Handler(Looper.getMainLooper()).postDelayed({ throw IllegalStateException(text) }, CRASH_DELAY_MILLIS)
        return "crashing"
    }

    private suspend fun state(): String {
        val testMode = onOff(graph.settings.get(SettingKeys.SAFETY).testMode)
        val build = BuildCommit.read(context)
        val session = graph.screen.value ?: return "screen=off testMode=$testMode $build"
        // Plan 4D, H4: whether an input method's window is on the screen, from Kestrel's current snapshot.
        val keyboard = session.snapshots.current().snapshot.windows.any { it.kind == WindowKind.INPUT_METHOD }
        return withContext(Dispatchers.Main) {
            val grid = session.grid
            "screen=on testMode=$testMode numbers=${session.numberBadges.size} labels=${onOff(session.labelsShowing)} " +
                "grid=${onOff(grid != null)} feedback=${onOff(session.feedbackText != null)} " +
                "gridSize=${grid?.let { "${it.rows}x${it.columns}" } ?: "none"} " +
                "magnification=${magnification(session.actions.zoomScale, session.system.magnifiedCentre())} " +
                "keyboard=${if (keyboard) "shown" else "hidden"} $build"
        }
    }

    // The scale with two decimals and the centre in screen pixels while the screen is magnified, as "2.00@540,1200".
    private fun magnification(scale: Float, centre: Pair<Float, Float>?): String =
        if (scale > 1f && centre != null) {
            String.format(Locale.ROOT, "%.2f@%d,%d", scale, centre.first.roundToInt(), centre.second.roundToInt())
        } else {
            "none"
        }

    private suspend fun testMode(intent: Intent): String {
        if (!intent.hasExtra(EXTRA_ON)) throw HookError("add --ez on true or --ez on false")
        val on = intent.getBooleanExtra(EXTRA_ON, true)
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
        return "testMode=${onOff(on)}"
    }

    private suspend fun tap(session: ScreenSession, intent: Intent): ActionResult {
        val long = intent.getBooleanExtra(EXTRA_LONG, false)
        if (intent.hasExtra(EXTRA_X) || intent.hasExtra(EXTRA_Y)) {
            if (!intent.hasExtra(EXTRA_X) || !intent.hasExtra(EXTRA_Y)) throw HookError("add both --ei x and --ei y")
            val x = intent.getIntExtra(EXTRA_X, 0)
            val y = intent.getIntExtra(EXTRA_Y, 0)
            return if (long) session.actions.longPressAt(x, y) else session.actions.tapAt(x, y)
        }
        val key = target(session, intent)
        return if (long) session.actions.longPress(key) else session.actions.tap(key)
    }

    private suspend fun overlay(session: ScreenSession, intent: Intent) {
        when (intent.getStringExtra(EXTRA_SHOW)) {
            "numbers" -> session.showNumbers(NumbersMode.ALL)
            "unlabeled" -> session.showNumbers(NumbersMode.UNLABELED)
            "labels" -> session.showLabels()
            "grid" -> session.showGrid()
            "feedback" -> session.showFeedback(intent.requiredText())
            "none" -> {
                session.hideNumbers()
                session.hideLabels()
                session.hideGrid()
            }
            else -> throw HookError("add --es show with numbers, unlabeled, labels, grid, feedback or none")
        }
    }

    /** The element that the extras name: by its key, or by its number while the numbers show. */
    private suspend fun target(session: ScreenSession, intent: Intent): String =
        optionalTarget(session, intent) ?: throw HookError("add --es key <element key> or --ei number <number>")

    private suspend fun optionalTarget(session: ScreenSession, intent: Intent): String? {
        intent.getStringExtra(EXTRA_KEY)?.let { return it }
        if (!intent.hasExtra(EXTRA_NUMBER)) return null
        val number = intent.getIntExtra(EXTRA_NUMBER, 0)
        val badges = withContext(Dispatchers.Main) { session.numberBadges }
        return badges.firstOrNull { it.number == number }?.key ?: throw HookError("no element shows the number $number")
    }

    private fun done(result: ActionResult): String = when (result) {
        is ActionResult.Done -> "done: ${result.method.name.lowercase()}"
        is ActionResult.Failed -> throw HookError(result.reason)
    }

    private fun Intent.requiredText(): String = getStringExtra(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    private inline fun <reified T : Enum<T>> Intent.choice(name: String): T {
        val value = getStringExtra(name)?.replace('-', '_')
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw HookError("add --es $name with one of ${enumValues<T>().joinToString { it.name.lowercase() }}")
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    /** A mistake in a command, which the answer reports. VoiceHooks throws it too. */
    class HookError(override val message: String) : Exception(message)

    companion object {
        const val ACTION_PREFIX = "dev.kestrel.hook."
        const val SNAPSHOT_FILE = "hook-snapshot.json"
        private const val CRASH_DELAY_MILLIS = 500L
        private const val EXTRA_KEY = "key"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_X = "x"
        private const val EXTRA_Y = "y"
        private const val EXTRA_LONG = "long"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_DIRECTION = "direction"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_SHOW = "show"
        private const val EXTRA_ON = "on"
    }
}
