package dev.kestrel.eval.task

import dev.kestrel.eval.device.AlarmDump
import dev.kestrel.eval.device.AlarmEntry
import dev.kestrel.eval.device.Alarms
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer
import java.time.DateTimeException
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The device's clock app, the one that handles AlarmClock's SET_ALARM: Google Clock on kestrel-api37, Samsung Clock on
 * the A71. Nothing here force-stops it, because `am force-stop` cancels every alarm of an app, also the owner's.
 */
object ClockApp {
    const val GOOGLE = "com.google.android.deskclock"
    const val SAMSUNG = "com.sec.android.app.clockpackage"

    /** The key of [RunScope.notes] that holds the clock's package. */
    const val NOTE = "clock"

    /** The label of the clocks' control that deletes a running timer. */
    const val DELETE = "Delete"

    /** The clock app's package, from `cmd package resolve-activity`, or null when no app sets alarms. */
    fun find(scope: RunScope): String? =
        scope.device.read("cmd package resolve-activity --brief -a android.intent.action.SET_ALARM").map { it.trim() }.lastOrNull { it.contains('/') }?.substringBefore('/')

    fun alarms(scope: RunScope): AlarmDump? = Alarms.parse(scope.device.read("dumpsys alarm"))

    /** The device's time zone (`persist.sys.timezone`), or UTC when it gives none that Java knows. */
    fun zone(scope: RunScope): ZoneId = try {
        ZoneId.of(scope.device.read("getprop persist.sys.timezone").joinToString("").trim())
    } catch (e: DateTimeException) {
        ZoneOffset.UTC
    }

    /**
     * The taps that delete the running test timer on the clock's timers page: Google Clock's Delete (kestrel-api37's
     * names the timer), Samsung Clock's Delete.
     */
    fun timerTaps(clock: String): List<List<TapCandidate>> = when (clock) {
        SAMSUNG -> listOf(SAMSUNG_TIMER)
        else -> listOf(listOf(TapCandidate(label = "Delete 5 minutes timer"), TapCandidate(label = DELETE), TapCandidate(contains = DELETE)))
    }

    /**
     * Samsung Clock's control that deletes its running timer, as One UI 5.1 shows it on the A71 (counted there on
     * 2026-11-24): "Delete", a clickable container around a clickable Button of the same label, so the first of the two
     * clickable elements, the container, which screen-tap reaches first; then older candidates.
     */
    val SAMSUNG_TIMER = listOf(TapCandidate(label = DELETE, state = "clickable", first = true), TapCandidate(label = "Cancel"), TapCandidate(label = DELETE))

    /**
     * What the clock's timers page shows while a timer runs, which the restore needs before it taps: on Samsung Clock the
     * running timer's Pause, one Button (beside it, Delete); Google Clock's own Delete names its timer, so it needs none.
     */
    fun timerRunningShown(scope: RunScope, clock: String): Boolean =
        clock != SAMSUNG || scope.hooks.screenHas(packageName = clock, label = "Pause", className = "Button").let { it["app"] == clock && it.int("count") == 1 }

    /**
     * The clock's running timers: its alarms on the elapsed clock (types 2 and 3), which both Google Clock and Samsung
     * Clock use for a timer, while its alarms and other wakeups use the wall clock.
     */
    fun timers(dump: AlarmDump, clock: String): List<AlarmEntry> = dump.of(clock).filter { it.type == 2 || it.type == 3 }
}

/**
 * alarm's first step: the task runs only on kestrel-api37. On the A71, and on any other device, the owner's real alarms
 * are in the clock app, and no sequence of the clock's taps was found that is safe there, so the task is untestable before
 * any change and before any restore is pushed (the controller's decision of 2026-11-24, after the reviews of Task 6 of
 * plan 6B).
 */
data object NoOwnerAlarms : Setup {
    const val WHY = "the owner's real alarms are on this phone"

    override fun apply(scope: RunScope): SetupResult =
        if (scope.device.kind == DeviceKind.EMULATOR) SetupResult.Ready else SetupResult.Untestable(WHY)
}

/**
 * alarm's time and its restore (design D8 and D9 of sub-project 6), on kestrel-api37 only ([NoOwnerAlarms]) and with
 * Google Clock. Before any change, the task is untestable when the clock holds an alarm or a timer: an alarm clock of it
 * in `dumpsys alarm` (also one at a time that is not the test's), a timer (its alarm on the elapsed clock) or any
 * notification of it, such as a paused timer's or an upcoming alarm's. So the test's time is always [TIME], in `{time}`,
 * `{hour}` and `{minute}`, and every alarm that the clock holds after the run is the run's. The restore clears Google
 * Clock's data ([AlarmCleanup]): the controller's decision of 2026-11-24, after the restore by the clock's own taps
 * failed twice after live runs.
 */
data object ClockFree : Setup {
    const val TIME = "6:45"
    private const val HOUR = 6
    private const val MINUTE = 45

    override fun apply(scope: RunScope): SetupResult {
        if (scope.device.kind != DeviceKind.EMULATOR) return SetupResult.Untestable(NoOwnerAlarms.WHY)
        val clock = ClockApp.find(scope) ?: return SetupResult.Untestable("no app sets alarms")
        if (clock != ClockApp.GOOGLE) return SetupResult.Untestable("its clock app is not Google Clock, the only one whose data the script clears")
        val dump = ClockApp.alarms(scope) ?: return SetupResult.Untestable("dumpsys alarm gives no clocks")
        if (AlarmCleanup.clocks(dump, clock).isNotEmpty() || ClockApp.timers(dump, clock).isNotEmpty() || Dumps.notification(scope.device.read("dumpsys notification"), clock)) {
            return SetupResult.Untestable("Google Clock holds an alarm or a timer, which its restore would delete")
        }
        scope.notes[ClockApp.NOTE] = clock
        scope.values.time = TIME
        scope.values.hour = "$HOUR"
        scope.values.minute = "$MINUTE"
        scope.restores.push("the test alarm at $TIME", AlarmCleanup.BY_HAND) { AlarmCleanup.run(scope) }
        return SetupResult.Ready
    }
}

/**
 * alarm's restore (design D9 of sub-project 6): `pm clear` of Google Clock, and only of it, which deletes every alarm and
 * timer that the run set, also a switched-off one, then the check that `dumpsys alarm` shows no alarm clock of it. It
 * refuses on any device but kestrel-api37, before any command, and the device's own check of each command
 * ([dev.kestrel.eval.device.Device.guarded]) refuses it on another device that took kestrel-api37's serial. Only alarm
 * clocks count, never the clock app's other wakeups.
 */
internal object AlarmCleanup {
    const val BY_HAND = "Clock > Alarms: delete every alarm."

    /** How long the check waits for the cleared clock's alarm clocks to leave `dumpsys alarm`. */
    private const val WAIT_SECONDS = 5

    fun run(scope: RunScope) {
        val clock = ClockApp.GOOGLE
        check(scope.device.kind == DeviceKind.EMULATOR) { "The test alarm is on ${scope.device.kind.label}, where the script clears no app's data." }
        val answer = scope.device.idempotent("pm clear $clock")
        check(answer.any { it.trim() == "Success" }) { "pm clear $clock did not answer Success." }
        var left = 0
        for (second in 0..WAIT_SECONDS) {
            if (second > 0) scope.clock.sleep(1.seconds)
            left = clocks(checkNotNull(ClockApp.alarms(scope)) { "dumpsys alarm gives no clocks." }, clock).distinct().size
            if (left == 0) return
        }
        error("Google Clock still holds $left alarm(s) after pm clear.")
    }

    /** The times of [clock]'s alarm clocks in [dump], never its other wakeups. */
    fun clocks(dump: AlarmDump, clock: String): List<Long> = dump.of(clock).filter { it.clock }.map { it.origWhen }

    /** Whether `dumpsys alarm` shows an alarm clock of [clock] at the next [hour]:[minute], or at the same time a day later. */
    fun set(scope: RunScope, clock: String, hour: Int, minute: Int): Boolean {
        val dump = checkNotNull(ClockApp.alarms(scope)) { "dumpsys alarm gives no clocks." }
        val next = Alarms.nextAt(dump.nowRtc, ClockApp.zone(scope), hour, minute)
        return clocks(dump, clock).any { at -> listOf(next, next + DAY).any { abs(at - it) < MINUTE } }
    }

    private const val MINUTE = 60_000L
    private const val DAY = 86_400_000L
}

/** A new alarm of the clock app at the next {time}, or at the same time a day later, in `dumpsys alarm` (design D8). */
data object NextAlarmAt : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val clock = scope.notes[ClockApp.NOTE] ?: return false
        val hour = scope.values.hour?.toIntOrNull() ?: return false
        val minute = scope.values.minute?.toIntOrNull() ?: return false
        return AlarmCleanup.set(scope, clock, hour, minute)
    }
}

/** A rehearsal's alarm at {time}, set through Android's alarm intent without the clock's screen; [ClockFree]'s restore deletes it. */
data object AlarmIntent : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val clock = scope.notes[ClockApp.NOTE] ?: throw SetupFailed("The clock app is not known: ClockFree did not run first.")
        scope.restores.change("the test alarm set", "Clock > Alarms: delete the alarm at ${scope.values.time}.") {
            // A lost answer is read back by the task's check.
            scope.device.once(
                "am start -W -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR ${scope.values.hour} " +
                    "--ei android.intent.extra.alarm.MINUTES ${scope.values.minute} --ez android.intent.extra.alarm.SKIP_UI true -p $clock",
            )
        }
        scope.clock.sleep(3.seconds)
        return SetupResult.Ready
    }
}

/**
 * timer's restore, pushed before the run (design D9 of sub-project 6). A timer of the owner's, running, paused or ringing
 * (an alarm of the clock on the elapsed clock, or an ongoing notification of the clock), makes the task untestable before
 * any change: the clock's timers page would show it beside the run's, and a tap could reach it. Else the clock's alarms
 * are recorded now, and after the run the one new alarm due within 6 minutes, the test timer, is deleted with the
 * clock's own taps on its timers page and checked. When the clock runs any other timer then, such as one that the owner
 * started during the run, the restore taps nothing and says to delete the test timer by hand.
 */
data object TimerCleanup : Setup {
    private const val BEFORE = "timer-before"
    private const val BY_HAND = "Clock > Timer: delete the 5-minute timer."

    /** How long the restore waits for the timers page to show the control that deletes the timer. */
    private const val PAGE_SECONDS = 10

    override fun apply(scope: RunScope): SetupResult {
        val clock = ClockApp.find(scope) ?: return SetupResult.Untestable("no app sets timers")
        val dump = ClockApp.alarms(scope) ?: return SetupResult.Untestable("dumpsys alarm gives no clocks")
        if (ClockApp.timers(dump, clock).isNotEmpty() || Dumps.ongoingNotification(scope.device.read("dumpsys notification"), clock)) {
            return SetupResult.Untestable("the clock app has a timer of the owner's, or shows one, which the run's own would not be told from")
        }
        scope.notes[ClockApp.NOTE] = clock
        scope.notes[BEFORE] = dump.of(clock).joinToString(",") { "${it.whenElapsed}" }
        scope.restores.push("the test timer", BY_HAND) {
            val new = newTimers(scope, clock)
            if (new.isEmpty()) return@push
            val running = ClockApp.timers(checkNotNull(ClockApp.alarms(scope)) { "dumpsys alarm gives no clocks." }, clock)
            check(new.size == 1 && running.all { it in new }) {
                "The clock runs ${(running + new).distinct().size} timers, so the script deleted none of them. $BY_HAND"
            }
            val taps = ClockApp.timerTaps(clock)
            scope.device.idempotent("am start -W -a android.intent.action.SHOW_TIMERS -p $clock")
            try {
                for (second in 1..PAGE_SECONDS) {
                    scope.clock.sleep(1.seconds)
                    ImmersiveTip.dismiss(scope)
                    if (taps.first().any { shown(scope, clock, it) } && ClockApp.timerRunningShown(scope, clock)) break
                }
                // The test timer is the clock's only one, so its page shows only its controls.
                if (ClockApp.timerRunningShown(scope, clock)) AppTaps(scope, clock).run(taps)
            } finally {
                scope.device.idempotent("input keyevent KEYCODE_HOME")
            }
            check(newTimers(scope, clock).isEmpty()) { "The test timer still runs in $clock. $BY_HAND" }
        }
        return SetupResult.Ready
    }

    private fun shown(scope: RunScope, clock: String, candidate: TapCandidate): Boolean {
        val answer = scope.hooks.screenHas(packageName = clock, label = candidate.label, contains = candidate.contains)
        return answer["app"] == clock && answer.int("count") >= 1
    }

    /** The clock's alarms due within 6 minutes that were not there before the run. */
    fun newTimers(scope: RunScope, clock: String): List<AlarmEntry> {
        val dump = checkNotNull(ClockApp.alarms(scope)) { "dumpsys alarm gives no clocks." }
        val before = scope.notes[BEFORE].orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
        return dump.of(clock).filter { it.whenElapsed !in before && it.whenElapsed - dump.nowElapsed in 0..6.minutes.inWholeMilliseconds }
    }
}

/** A new alarm of the clock app due 4 to 5 minutes from now, with 10 s to spare, and the clock's ongoing notification (design D8). */
data object TimerRunning : Check {
    override fun passes(scope: RunScope, answer: HookAnswer): Boolean {
        val clock = scope.notes[ClockApp.NOTE] ?: return false
        val now = ClockApp.alarms(scope)?.nowElapsed ?: return false
        val due = (4.minutes - 10.seconds).inWholeMilliseconds..(5.minutes + 10.seconds).inWholeMilliseconds
        return TimerCleanup.newTimers(scope, clock).any { it.whenElapsed - now in due } && Dumps.ongoingNotification(scope.device.read("dumpsys notification"), clock)
    }
}

/** A rehearsal's 5-minute timer, started through Android's timer intent without the clock's screen; [TimerCleanup]'s restore deletes it. */
data object TimerIntent : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val clock = scope.notes[ClockApp.NOTE] ?: throw SetupFailed("The clock app is not known: TimerCleanup did not run first.")
        scope.restores.change("the test timer started", "Clock > Timer: delete the 5-minute timer.") {
            // A lost answer is read back by the task's check.
            scope.device.once("am start -W -a android.intent.action.SET_TIMER --ei android.intent.extra.alarm.LENGTH 300 --ez android.intent.extra.alarm.SKIP_UI true -p $clock")
        }
        scope.clock.sleep(3.seconds)
        return SetupResult.Ready
    }
}
