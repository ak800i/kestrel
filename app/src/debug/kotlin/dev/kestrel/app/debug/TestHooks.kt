package dev.kestrel.app.debug

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Bundle
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

/** A test hook's answer: [OK] and the answer's text, or [FAILED] and the reason; [TestHookProvider] returns it to the caller. */
internal data class HookReply(val code: Int, val text: String) {
    companion object {
        const val OK = 0
        const val FAILED = 1

        fun ok(text: String) = HookReply(OK, text)

        fun failed(message: String) = HookReply(FAILED, "error: $message")
    }
}

/**
 * Runs the test-hook commands (design spec, section 16.1): the command, such as `GOAL_CONTROL`, with its arguments in
 * [extras], as [TestHookProvider] passes them on; `scripts/hook.ps1` lists them.
 */
internal class TestHooks(private val graph: AppGraph, private val context: Context) {
    private val cacheDir: File get() = context.cacheDir

    suspend fun run(command: String, extras: Bundle): HookReply = try {
        HookReply.ok(answer(command, extras))
    } catch (error: HookError) {
        HookReply.failed(error.message)
    }

    private suspend fun answer(command: String, extras: Bundle): String {
        if (command == "STATE") return state()
        if (command == "TEST_MODE") return testMode(extras)
        if (command == "CRASH") return crash(extras.requiredText())
        if (command in SpikeHooks.COMMANDS) return SpikeHooks(context, graph).run(command, extras)
        if (command in VoiceHooks.COMMANDS) return VoiceHooks(graph).run(command, extras)
        if (command in ExpectHooks.COMMANDS) return ExpectHooks(graph).run(command, extras)
        if (command in ScreenHooks.COMMANDS) return ScreenHooks(graph).run(command, extras)
        if (command in EvalHooks.COMMANDS) return EvalHooks(graph, context).run(command, extras)
        if (command in SessionHooks.COMMANDS) return SessionHooks(graph, context).run(command, extras)
        if (command in ProviderHooks.COMMANDS) return ProviderHooks(graph).run(command, extras)
        if (command in AgentHooks.COMMANDS) return AgentHooks(graph, context).run(command, extras)
        if (command in GoalServiceHooks.COMMANDS) return GoalServiceHooks(graph, context).run(command, extras)
        if (command in ConversationHooks.COMMANDS) return ConversationHooks(graph).run(command, extras)
        if (command == "PROFILES") return profiles()
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        val actions = session.actions
        return when (command) {
            "SNAPSHOT" -> save(ScreenJson.encode(session.snapshots.current().snapshot))
            "TAP" -> done(tap(session, extras))
            "TYPE" -> done(actions.setText(target(session, extras), extras.requiredText()))
            "SCROLL" -> done(actions.scroll(extras.choice<ScrollDirection>(EXTRA_DIRECTION), optionalTarget(session, extras)))
            "GLOBAL" -> done(actions.global(extras.choice<GlobalAction>(EXTRA_ACTION)))
            "OVERLAY" -> {
                overlay(session, extras)
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
     * call's answer travels in one binder transaction, which must stay under Android's limit of about 1 MB. `scripts/hook.ps1` reads the file with
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

    private suspend fun testMode(extras: Bundle): String {
        if (!extras.containsKey(EXTRA_ON)) throw HookError("add --ez on true or --ez on false")
        val on = extras.getBoolean(EXTRA_ON, true)
        graph.settings.set(SettingKeys.SAFETY, graph.settings.get(SettingKeys.SAFETY).copy(testMode = on))
        return "testMode=${onOff(on)}"
    }

    private suspend fun tap(session: ScreenSession, extras: Bundle): ActionResult {
        val long = extras.getBoolean(EXTRA_LONG, false)
        if (extras.containsKey(EXTRA_X) || extras.containsKey(EXTRA_Y)) {
            if (!extras.containsKey(EXTRA_X) || !extras.containsKey(EXTRA_Y)) throw HookError("add both --ei x and --ei y")
            val x = extras.getInt(EXTRA_X, 0)
            val y = extras.getInt(EXTRA_Y, 0)
            return if (long) session.actions.longPressAt(x, y) else session.actions.tapAt(x, y)
        }
        val key = target(session, extras)
        return if (long) session.actions.longPress(key) else session.actions.tap(key)
    }

    private suspend fun overlay(session: ScreenSession, extras: Bundle) {
        when (extras.getString(EXTRA_SHOW)) {
            "numbers" -> session.showNumbers(NumbersMode.ALL)
            "unlabeled" -> session.showNumbers(NumbersMode.UNLABELED)
            "labels" -> session.showLabels()
            "grid" -> session.showGrid()
            // Through the bar's one writer, so that it knows whose text the bar shows (plan 7F).
            "feedback" -> graph.feedbackBar.show(extras.requiredText())
            "none" -> {
                session.hideNumbers()
                session.hideLabels()
                session.hideGrid()
            }
            else -> throw HookError("add --es show with numbers, unlabeled, labels, grid, feedback or none")
        }
    }

    /** The element that the extras name: by its key, or by its number while the numbers show. */
    private suspend fun target(session: ScreenSession, extras: Bundle): String =
        optionalTarget(session, extras) ?: throw HookError("add --es key <element key> or --ei number <number>")

    private suspend fun optionalTarget(session: ScreenSession, extras: Bundle): String? {
        extras.getString(EXTRA_KEY)?.let { return it }
        if (!extras.containsKey(EXTRA_NUMBER)) return null
        val number = extras.getInt(EXTRA_NUMBER, 0)
        val badges = withContext(Dispatchers.Main) { session.numberBadges }
        return badges.firstOrNull { it.number == number }?.key ?: throw HookError("no element shows the number $number")
    }

    private fun done(result: ActionResult): String = when (result) {
        is ActionResult.Done -> "done: ${result.method.name.lowercase()}"
        is ActionResult.Failed -> throw HookError(result.reason)
    }

    private fun Bundle.requiredText(): String = getString(EXTRA_TEXT) ?: throw HookError("add --es text <text>")

    private inline fun <reified T : Enum<T>> Bundle.choice(name: String): T {
        val value = getString(name)?.replace('-', '_')
        return enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) }
            ?: throw HookError("add --es $name with one of ${enumValues<T>().joinToString { it.name.lowercase() }}")
    }

    private fun onOff(value: Boolean) = if (value) "on" else "off"

    /** A mistake in a command, which the answer reports. VoiceHooks throws it too. */
    class HookError(override val message: String) : Exception(message)

    companion object {
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
