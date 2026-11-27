package dev.kestrel.app.debug

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.kestrel.app.AppGraph
import dev.kestrel.app.conversation.InputIds
import dev.kestrel.app.debug.TestHooks.HookError
import dev.kestrel.app.goal.GoalAction
import dev.kestrel.app.goal.GoalNotice
import dev.kestrel.app.goal.GoalService
import dev.kestrel.conversation.model.InputChannel
import dev.kestrel.conversation.model.InputIntent
import dev.kestrel.conversation.model.OwnerInput
import dev.kestrel.privacy.logs.describeWithoutMessages
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GlobalAction
import dev.kestrel.screen.act.PressDispatch
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
 * Android's rules for a start from a notification apply, with the press's number claimed as the hooks' ([ButtonClaims];
 * plan 7G), says which collapsed rows it had to expand before the press, and
 * closes the shade again however the press ends; stops the goal or Kestrel's own request of the hooks' own conversation,
 * and only that one (stop-typed; plan 7G); gives the conversation a touch, as the touch watcher gives it (D13), where a real touch on the screen is not safe, as on the A71;
 * asks Android for the audio focus with and without the speech's types (spike S9); arms or takes back a refusal of the
 * service's next start; or has a button's own PendingIntent sent a few seconds later, to a process that has ended
 * meanwhile, with its claim saved for that process. The answers never contain words.
 */
internal class GoalServiceHooks(private val graph: AppGraph, private val context: Context) {
    private val buttons = NoticeButtons()

    suspend fun run(command: String, intent: Intent): String = when (command) {
        "GOAL_SERVICE_STATE" -> GoalServiceAnswers.state(
            graph.goalForeground.status(),
            GoalService.runningTypes(),
            FocusProbe.requests.now(),
            GoalStartRefusal.of(graph).armed,
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
            GoalStartRefusal.of(graph).arm()
            "refuse=armed"
        }
        "refuse-off" -> GoalServiceAnswers.refuseOff(GoalStartRefusal.of(graph).take())
        "send-later" -> sendLater(intent.getStringExtra(EXTRA_ACTION))
        // Only the goal or Kestrel's own request of the hooks' own conversation, or the hooks' own request while no
        // conversation is open (plan 7G): the conversation's loop checks their mark at the moment that it takes the stop, so
        // the work of a conversation that the owner opened or that an act of his reached is never stopped. `stop=none` when
        // no such work runs.
        "stop-typed" -> {
            val stop = VoiceHooks.untagged(graph, OwnerInput(InputIds.next(), InputChannel.VOICE, InputIntent.Stop, System.currentTimeMillis()))
            if (graph.conversations.stopIf(stop, HookConversations::ownsWork)) "stop=sent" else "stop=none"
        }
        else -> throw HookError("add --es do with one of tap, touch, speak, focus, refuse, refuse-off, send-later or stop-typed")
    }

    private suspend fun tap(name: String?): String {
        val action = actionOf(name)
        val notice = graph.goalForeground.notice() ?: throw HookError("the goal service shows no notification")
        if (action !in notice.actions) throw HookError("the goal notification has no ${action.name.lowercase()} now")
        val goal = notice.goal ?: throw HookError("the goal notification names no goal")
        val session = graph.screen.value ?: throw HookError("Kestrel's accessibility service is off")
        // The press is the hooks', so a button that gives the conversation an input has its number claimed at the moment that
        // the press goes to SystemUI, and the claim stays whatever the press answers afterwards, unless Android refused the
        // press's gesture: the receiver uses it up as the button's broadcast arrives, and an unused one expires
        // (ButtonClaims; plan 7G). Open gives no input.
        val dispatch = action.intent?.let { ButtonClaims.current().pressing(action, goal) }
        // SystemUI's shade closes again however the press ends, also after an error or the hook's timeout, so that the phone
        // is as the hook found it (AGENTS.md's limits on the A71).
        val seen = ShadeSeen()
        var shadeClosed = false
        var expanded = emptyList<ShadeExpansion>()
        val failure = try {
            session.actions.global(GlobalAction.NOTIFICATIONS)
            val tapped = tapInShade(session, notice, action, seen, dispatch)
            expanded = tapped.expanded
            when (tapped) {
                is NoticeButtons.Tapped.Pressed -> null
                is NoticeButtons.Tapped.Refused -> tapped.reason
                is NoticeButtons.Tapped.NotShown -> "the goal notification's ${action.name.lowercase()} is not in the shade"
            }
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

    // Null once the button with [key] was pressed, else why not. [dispatch] learns the moments that the press goes to SystemUI.
    private suspend fun pressInShade(
        session: ScreenSession,
        notice: GoalNotice,
        action: GoalAction,
        key: String,
        dispatch: PressDispatch?,
    ): String? {
        // By the button's key, which the press checks again in every snapshot that it acts on: a row that a new notification
        // moved, or another app's button, is never pressed in its place (AGENTS.md's limits on the A71). Once only, so that a
        // Continue is never sent twice.
        val pressed = session.actions.tap(
            key,
            expected = { element -> buttons.isButton(element, action.label) },
            policy = PressPolicy.ONCE,
            guard = { snapshot -> buttons.button(snapshot, notice.title, action.label) == key },
            dispatch = dispatch,
        )
        return (pressed as? ActionResult.Failed)?.reason
    }

    /**
     * Hands the PendingIntent of the goal notification's button, as Android holds it on Kestrel's posted notification, to
     * AlarmManager, which sends it [SEND_LATER_MILLIS] later (Task 8, Step 8a of plan 7E; the second review's R4-M1). The
     * step ends Kestrel's process meanwhile, and the system delivers the button's broadcast to a new process as it
     * delivers the owner's tap. Nothing is tapped on the screen, so a row that moves in the shade can never turn it into a
     * tap on another app's notification. `send=scheduled`, or `send=none` when Kestrel's goal notification shows no such
     * button. USE_EXACT_ALARM in the debug manifest allows the exact alarm without a setting of the phone. On a phone
     * without a boot count, a button that gives an input is refused before the alarm, because its claim cannot reach the
     * new process ([ButtonClaims.reachesNewProcesses]).
     */
    private fun sendLater(name: String?): String {
        val action = actionOf(name)
        val status = graph.goalForeground.status()
        val id = NoticeIntents.idOf(status, action) ?: return "send=none"
        val goal = status.counting?.notice?.goal ?: return "send=none"
        val posted = context.getSystemService(NotificationManager::class.java).activeNotifications.map { shown ->
            NoticeIntents.Posted(shown.id, (shown.notification.actions ?: emptyArray()).map { it.title.toString() to it.actionIntent })
        }
        val button = NoticeIntents.intentOf(posted, id, action.label) ?: return "send=none"
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (!alarms.canScheduleExactAlarms()) throw HookError("Kestrel may not set an exact alarm")
        // The press is the hooks', and its broadcast may reach a new process, so its claim is armed in the claims' file before
        // the alarm is set, valid from the alarm's own trigger time, the first moment at which AlarmManager may send it
        // (ButtonClaims; plan 7G), and taken back when AlarmManager does not take the alarm. setExact as plan 7E's Step 8a set
        // it, so that the broadcast reaches Kestrel as the owner's tap does, without the exemptions of an alarm that is
        // allowed while idle; an alarm that Doze defers beyond the claim's life comes unclaimed, and counts as the owner's.
        val claims = ButtonClaims.current()
        if (action.intent != null && !claims.reachesNewProcesses) {
            throw HookError("this phone has no boot count, so send-later's press cannot be claimed in a new process")
        }
        val due = SystemClock.elapsedRealtime() + SEND_LATER_MILLIS
        val armed = action.intent?.let { claims.arm(action, goal, fromMillis = due) }
        try {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, button)
        } catch (error: Exception) {
            armed?.let(claims::disarm)
            throw error
        }
        return "send=scheduled"
    }

    private fun actionOf(name: String?): GoalAction = GoalAction.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: throw HookError("add --es action with one of stop, open, continue or cancel")

    // The shade opens with an animation, and a notification shows its buttons only once its row, and the row of the group
    // that Android put it in, are expanded: the search asks for those expansions, one in each snapshot, the group's first,
    // and presses the button once a snapshot shows it ([NoticeButtons.findAndPress]). Each snapshot tells [seen] whether
    // the open shade shows tiles that Kestrel knows.
    private suspend fun tapInShade(
        session: ScreenSession,
        notice: GoalNotice,
        action: GoalAction,
        seen: ShadeSeen,
        dispatch: PressDispatch?,
    ): NoticeButtons.Tapped =
        buttons.findAndPress(
            notice.title,
            action.label,
            SHADE_TRIES,
            look = {
                delay(SHADE_MILLIS)
                session.snapshots.current().snapshot.also { if (buttons.showsTiles(it)) seen.tilesKnown = true }
            },
            expand = { session.actions.expand(it) },
            press = { key -> pressInShade(session, notice, action, key, dispatch) },
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
