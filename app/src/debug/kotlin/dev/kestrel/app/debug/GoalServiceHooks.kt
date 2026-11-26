package dev.kestrel.app.debug

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.GoalService
import dev.kestrel.privacy.logs.describeWithoutMessages
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.PressPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The goal service's hooks (plan 7E; design 10.6 of sub-project 7). goal-service-state reads the service, whether a
 * refusal of its next start is armed, and the audio-focus requests. goal-control presses a button of the goal notification
 * in SystemUI's shade by its key, so that SystemUI sends the button's broadcast as it does for the owner's finger and
 * Android's rules for a start from a notification apply, says which collapsed rows it had to expand before the press, and
 * closes the shade again however the press ends; gives the
 * conversation a touch, as the touch watcher gives it (D13), where a real touch on the screen is not safe, as on the A71;
 * asks Android for the audio focus with and without the speech's types (spike S9); arms or takes back a refusal of the
 * service's next start; or has a button's own PendingIntent sent a few seconds later, to a process that has ended
 * meanwhile. The answers never contain words.
 */
internal class GoalServiceHooks(private val graph: AppGraph, private val context: Context) {
    private val buttons = NoticeButtons()

    suspend fun run(command: String, intent: Intent): String = when (command) {
        "GOAL_SERVICE_STATE" -> GoalServiceAnswers.state(
            graph.goalForeground.status(),
            GoalService.runningTypes(),
            FocusProbe.requests.now(),
            graph.goalStartRefusal.armed,
        )
        "GOAL_CONTROL" -> control(intent)
        else -> throw HookError("unknown command '$command'")
    }

    private suspend fun control(intent: Intent): String = when (val wanted = intent.getStringExtra(EXTRA_DO)) {
        "tap" -> tap(intent.getStringExtra(EXTRA_ACTION))
        "touch" -> {
            graph.conversations.touched()
            "touch=sent"
        }
        "speak", "focus" -> {
            FocusProbe.requests.asked()
            // After the hook's broadcast has returned, so that its own standing does not count.
            graph.scope.launch {
                delay(FocusProbe.DELAY_MILLIS)
                FocusProbe.requests.answered(FocusProbe.request(context, graph.goalForeground, speaking = wanted == "speak"))
            }
            "$wanted=sent"
        }
        // The next start of the goal service is refused as Android refuses it, once (plan 7E, Task 8, Step 8a); refuse-off
        // takes an armed refusal back when a check stops before that start.
        "refuse" -> {
            graph.goalStartRefusal.arm()
            "refuse=armed"
        }
        "refuse-off" -> GoalServiceAnswers.refuseOff(graph.goalStartRefusal.take())
        "send-later" -> sendLater(intent.getStringExtra(EXTRA_ACTION))
        else -> throw HookError("add --es do with one of tap, touch, speak, focus, refuse, refuse-off or send-later")
    }

    private suspend fun tap(name: String?): String {
        val action = actionOf(name)
        val notice = graph.goalForeground.notice() ?: throw HookError("the goal service shows no notification")
        if (action !in notice.actions) throw HookError("the goal notification has no ${action.name.lowercase()} now")
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        // SystemUI's shade closes again however the press ends, also after an error or the hook's timeout, so that the phone
        // is as the hook found it (AGENTS.md's limits on the A71).
        val seen = ShadeSeen()
        var shadeClosed = false
        var expanded = emptyList<ShadeExpansion>()
        val failure = try {
            session.actions.global(GlobalAction.NOTIFICATIONS)
            val found = buttonInShade(session, notice, action, seen)
            expanded = found.expanded
            found.key?.let { pressInShade(session, notice, action, it) }
                ?: "the goal notification's ${action.name.lowercase()} is not in the shade"
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            "the press failed: ${error.describeWithoutMessages()}"
        } finally {
            shadeClosed = withContext(NonCancellable) { closeShade(session, seen) }
        }
        if (failure != null) throw HookError(GoalServiceAnswers.notTapped(failure, shadeClosed))
        return GoalServiceAnswers.tapped(action, expanded, shadeClosed)
    }

    /**
     * Closes SystemUI's shade and says whether it closed, as the screen shows it. SystemUI offers its dismiss only while the
     * shade is expanded, so Android refuses it once SystemUI closes the shade itself, as it does after Open and after a
     * button that asks for the unlock; the refusal says nothing about the shade, and the screen decides. "Closed" needs the
     * screen's evidence: on a locked phone, only the tiles that [seen] saw in the open shade tell it from the lock screen.
     */
    private suspend fun closeShade(session: ScreenSession, seen: ShadeSeen): Boolean {
        session.actions.global(GlobalAction.DISMISS_SHADE)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        repeat(CLOSE_TRIES) {
            delay(SHADE_MILLIS)
            if (!buttons.shadeOpen(session.snapshots.current().snapshot, keyguard.isKeyguardLocked, seen.tilesKnown)) return true
        }
        return false
    }

    // Null once the button with [key] was pressed, else why not.
    private suspend fun pressInShade(session: ScreenSession, notice: GoalNotice, action: GoalAction, key: String): String? {
        // By the button's key, which the press checks again in every snapshot that it acts on: a row that a new notification
        // moved, or another app's button, is never pressed in its place (AGENTS.md's limits on the A71). Once only, so that a
        // Continue is never sent twice.
        val pressed = session.actions.tap(
            key,
            expected = { element -> buttons.isButton(element, action.label) },
            policy = PressPolicy.ONCE,
            guard = { snapshot -> buttons.button(snapshot, notice.title, action.label) == key },
        )
        return (pressed as? ActionResult.Failed)?.reason
    }

    /**
     * Hands the PendingIntent of the goal notification's button, as Android holds it on Kestrel's posted notification, to
     * AlarmManager, which sends it [SEND_LATER_MILLIS] later (Task 8, Step 8a of plan 7E; the second review's R4-M1). The
     * step ends Kestrel's process meanwhile, and the system delivers the button's broadcast to a new process as it
     * delivers the owner's tap. Nothing is tapped on the screen, so a row that moves in the shade can never turn it into a
     * tap on another app's notification. `send=scheduled`, or `send=none` when Kestrel's goal notification shows no such
     * button. USE_EXACT_ALARM in the debug manifest allows the exact alarm without a setting of the phone.
     */
    private fun sendLater(name: String?): String {
        val action = actionOf(name)
        val id = NoticeIntents.idOf(graph.goalForeground.status(), action) ?: return "send=none"
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.map { shown ->
            NoticeIntents.Posted(shown.id, (shown.notification.actions ?: emptyArray()).map { it.title.toString() to it.actionIntent })
        }
        val button = NoticeIntents.intentOf(posted, id, action.label) ?: return "send=none"
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!alarms.canScheduleExactAlarms()) throw HookError("Kestrel may not set an exact alarm")
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + SEND_LATER_MILLIS, button)
        return "send=scheduled"
    }

    private fun actionOf(name: String?): GoalAction = GoalAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: throw HookError("add --es action with one of stop, open, continue or cancel")

    // The shade opens with an animation, and a notification shows its buttons only once its row, and the row of the group
    // that Android put it in, are expanded: the key of the button in Kestrel's own row, or null, and the expansions that the
    // search asked for, one in each snapshot, the group's first ([NoticeButtons.find]). Each snapshot tells [seen] whether
    // the open shade shows tiles that Kestrel knows.
    private suspend fun buttonInShade(session: ScreenSession, notice: GoalNotice, action: GoalAction, seen: ShadeSeen): NoticeButtons.Found =
        buttons.find(
            notice.title,
            action.label,
            SHADE_TRIES,
            look = {
                delay(SHADE_MILLIS)
                session.snapshots.current().snapshot.also { if (buttons.showsTiles(it)) seen.tilesKnown = true }
            },
            expand = { session.actions.expand(it) },
        )

    /**
     * What one press saw of the shade that it opened: whether the shade showed a Quick Settings tile that
     * [dev.kestrel.agent.observe.SystemUiParts] knows. Only then are this phone's tiles known, and only then can the hook
     * tell the closed shade from the lock screen ([NoticeButtons.shadeOpen]).
     */
    private class ShadeSeen {
        var tilesKnown = false
    }

    companion object {
        val COMMANDS = setOf("GOAL_SERVICE_STATE", "GOAL_CONTROL")
        private const val EXTRA_DO = "do"
        private const val EXTRA_ACTION = "action"
        private const val SHADE_TRIES = 5
        private const val CLOSE_TRIES = 5
        private const val SHADE_MILLIS = 600L

        // Android sends no app's alarm sooner than 5 s after it was set (AlarmManager's minimum futurity).
        private const val SEND_LATER_MILLIS = 5_000L
    }
}
