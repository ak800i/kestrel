package dev.kestrel.eval.task

import dev.kestrel.eval.device.AlarmDump
import dev.kestrel.eval.device.AlarmEntry
import dev.kestrel.eval.device.Alarms
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
     * The taps that delete the test alarm (design D9), candidates that Task 10 confirms on kestrel-api37 and Task 12 on the
     * A71: Google Clock's row expanded, then its Delete; Samsung Clock's long press on the alarm's time, "06:45" on a 24-hour
     * clock or "6:45", then Delete.
     */
    fun alarmTaps(clock: String): List<List<TapCandidate>> = when (clock) {
        SAMSUNG -> listOf(listOf(TapCandidate(contains = "0{time}", long = true), TapCandidate(contains = "{time}", long = true)), DELETE)
        else -> listOf(listOf(TapCandidate(label = "Expand alarm"), TapCandidate(contains = "Expand")), DELETE)
    }

    /** The taps that delete the running test timer on the clock's timers page: Google Clock's Delete, Samsung Clock's Cancel. */
    fun timerTaps(clock: String): List<List<TapCandidate>> = when (clock) {
        SAMSUNG -> listOf(listOf(TapCandidate(label = "Cancel"), TapCandidate(label = "Delete")))
        else -> listOf(DELETE)
    }

    private val DELETE = listOf(TapCandidate(label = "Delete"), TapCandidate(contains = "Delete"))
}

/**
 * alarm's time (design D8 and D20 of sub-project 6): the first of 6:45, 6:40, 6:35 and 6:50 at which the owner has no
 * alarm, in `{time}`, `{hour}` and `{minute}`, so that the test never touches an alarm of the owner's. Each time is
 * counted as "6:45" and as a 24-hour clock's "06:45". A time is not free
 * when `dumpsys alarm` shows an alarm of the clock there (an enabled alarm anywhere in the list), or when any page of the
 * clock's list of alarms shows one (also one that is switched off): the list is read to its end, page by page, until a
 * page adds no time and the first row of the list (its hash from `screen-has`) no longer changes, at most [PAGES] pages.
 * The list is left with the home key. Its restore deletes the test alarm with the clock's own taps when `dumpsys alarm`
 * shows one at that time and none was there before the run, and checks that it left.
 */
data class ClockFree(val times: List<String> = TIMES) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        val clock = ClockApp.find(scope) ?: return SetupResult.Untestable("no app sets alarms")
        scope.notes[ClockApp.NOTE] = clock
        val setBefore = times.filter { time -> numbers(time).let { (hour, minute) -> AlarmCleanup.set(scope, clock, hour, minute) } }
        scope.notes[SET_BEFORE] = setBefore.joinToString(",")
        val unset = times - setBefore.toSet()
        scope.restores.push("the home screen in front", "Press Home.") { scope.device.idempotent("input keyevent KEYCODE_HOME") }
        scope.restores.change("the clock's alarms shown", "Press Home.") { scope.device.idempotent("am start -W -a android.intent.action.SHOW_ALARMS -p $clock") }
        scope.clock.sleep(3.seconds)
        val shown = mutableSetOf<String>()
        var last: String? = null
        for (page in 1..PAGES) {
            // screen-has compares whole words, so a 24-hour clock's "06:45" is counted in that form too.
            val counts = unset.map { time -> time to listOf(time, "0$time").map { scope.hooks.screenHas(packageName = clock, contains = it) } }
            if (counts.any { (_, answers) -> answers.any { it["app"] != clock } }) {
                scope.device.idempotent("input keyevent KEYCODE_HOME")
                return SetupResult.Untestable("the clock app's alarms did not show")
            }
            val known = shown.size
            counts.filter { (_, answers) -> answers.any { it.int("count") > 0 } }.forEach { shown.add(it.first) }
            val first = scope.hooks.screenHas(packageName = clock, state = "scrollable")["first"]
            if (page > 1 && shown.size == known && first == last) break
            last = first
            // A lost answer is read back by the next page's count.
            scope.device.once("input swipe 540 1700 540 700 300")
            scope.clock.sleep(1.seconds)
        }
        scope.device.idempotent("input keyevent KEYCODE_HOME")
        val free = unset.firstOrNull { it !in shown } ?: return SetupResult.Untestable("the clock app has an alarm at each of the test's times")
        val (hour, minute) = numbers(free)
        scope.values.time = free
        scope.values.hour = "$hour"
        scope.values.minute = "$minute"
        scope.restores.push("the test alarm at $free", "Clock > Alarms: delete the alarm at $free.") {
            AlarmCleanup.run(scope, clock, hour, minute, free, before = free in scope.notes[SET_BEFORE].orEmpty().split(','))
        }
        return SetupResult.Ready
    }

    private fun numbers(time: String): List<Int> = time.split(':').map { it.toInt() }

    companion object {
        val TIMES = listOf("6:45", "6:40", "6:35", "6:50")
        const val PAGES = 10

        /** The key of [RunScope.notes] that holds the candidate times at which `dumpsys alarm` showed an alarm before the run. */
        const val SET_BEFORE = "alarms-set-before"
    }
}

/** The test alarm's deletion by the clock's own taps, checked in `dumpsys alarm` (design D9 of sub-project 6). */
internal object AlarmCleanup {
    /** Deletes the alarm at [time]; one that was set [before] the run is the owner's, and is left with a warning. */
    fun run(scope: RunScope, clock: String, hour: Int, minute: Int, time: String, before: Boolean = false) {
        if (before) {
            scope.console.warn("The alarm at $time was set before the run, so it is the owner's and stays; check it in the clock app.")
            return
        }
        if (!set(scope, clock, hour, minute)) return
        scope.device.idempotent("am start -W -a android.intent.action.SHOW_ALARMS -p $clock")
        scope.clock.sleep(3.seconds)
        AppTaps(scope, clock).run(ClockApp.alarmTaps(clock))
        scope.device.idempotent("input keyevent KEYCODE_HOME")
        check(!set(scope, clock, hour, minute)) { "The test alarm at $time is still set in $clock." }
    }

    /** Whether `dumpsys alarm` shows an alarm clock of [clock] at the next [hour]:[minute], or at the same time a day later. */
    fun set(scope: RunScope, clock: String, hour: Int, minute: Int): Boolean {
        val dump = checkNotNull(ClockApp.alarms(scope)) { "dumpsys alarm gives no clocks." }
        val next = Alarms.nextAt(dump.nowRtc, ClockApp.zone(scope), hour, minute)
        return dump.of(clock).any { alarm -> alarm.type <= 1 && listOf(next, next + DAY).any { abs(alarm.origWhen - it) < MINUTE } }
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
 * timer's restore, pushed before the run (design D9 of sub-project 6): the clock's alarms are recorded now, and after
 * the run a new one due within 6 minutes, the test timer, is deleted with the clock's own taps on its timers page and
 * checked.
 */
data object TimerCleanup : Setup {
    private const val BEFORE = "timer-before"

    override fun apply(scope: RunScope): SetupResult {
        val clock = ClockApp.find(scope) ?: return SetupResult.Untestable("no app sets timers")
        val dump = ClockApp.alarms(scope) ?: return SetupResult.Untestable("dumpsys alarm gives no clocks")
        scope.notes[ClockApp.NOTE] = clock
        scope.notes[BEFORE] = dump.of(clock).joinToString(",") { "${it.whenElapsed}" }
        scope.restores.push("the test timer", "Clock > Timer: delete the 5-minute timer.") {
            if (newTimers(scope, clock).isEmpty()) return@push
            scope.device.idempotent("am start -W -a android.intent.action.SHOW_TIMERS -p $clock")
            scope.clock.sleep(3.seconds)
            AppTaps(scope, clock).run(ClockApp.timerTaps(clock))
            scope.device.idempotent("input keyevent KEYCODE_HOME")
            check(newTimers(scope, clock).isEmpty()) { "The test timer still runs in $clock." }
        }
        return SetupResult.Ready
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
