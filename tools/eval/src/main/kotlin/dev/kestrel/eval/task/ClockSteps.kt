package dev.kestrel.eval.task

import dev.kestrel.eval.device.AlarmDump
import dev.kestrel.eval.device.AlarmEntry
import dev.kestrel.eval.device.Alarms
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.Dumps
import dev.kestrel.eval.hook.HookAnswer
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
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

    /**
     * Google Clock's labels, which Task 10 confirms on kestrel-api37: an alarm's card (a CardView whose label holds its
     * time), which opens the alarm's editor sheet, and the sheet's Delete and Save.
     */
    const val CARD = "CardView"
    const val DELETE = "Delete"
    const val SAVE = "Save"

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
     * names the timer), Samsung Clock's Cancel.
     */
    fun timerTaps(clock: String): List<List<TapCandidate>> = when (clock) {
        SAMSUNG -> listOf(listOf(TapCandidate(label = "Cancel"), TapCandidate(label = "Delete")))
        else -> listOf(listOf(TapCandidate(label = "Delete 5 minutes timer"), TapCandidate(label = DELETE), TapCandidate(contains = DELETE)))
    }
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
 * Google Clock's list of alarms, read page by page through `screen-has` (design D8 of sub-project 6): on each page the
 * counts of the times' forms and the hash of the list's first row in view. The list's top and its end count only when two
 * swipes in a row changed neither; a list that does not scroll is one page. A list whose first row has no name, or whose
 * top or end did not show within [PAGES] swipes, is not known ([Walk.UNKNOWN]).
 */
internal object AlarmList {
    const val PAGES = 10
    private const val DOWN = "input swipe 540 1700 540 700 300"
    private const val UP = "input swipe 540 700 540 1700 300"

    data class Page(val counts: Map<String, Int>, val first: String?, val scrolls: Boolean)

    enum class Walk { ENDED, FOUND, UNKNOWN, ELSEWHERE }

    /** A time's two forms, "6:45" and a 24-hour clock's "06:45"; screen-has compares whole words, so it tells them apart. */
    fun forms(time: String): List<String> = listOf(time, "0$time")

    /**
     * Scrolls the list to its top, then reads it down to its end, or until [found] holds for a page ([Walk.FOUND]); with
     * [className], a page counts only the elements of that class that hold a form, such as the alarms' cards.
     */
    fun walk(scope: RunScope, clock: String, forms: List<String>, className: String? = null, found: (Page) -> Boolean): Walk {
        val top = go(scope, clock, forms, null, UP) { false }
        return if (top == Walk.ENDED) go(scope, clock, forms, className, DOWN, found) else top
    }

    /** Scrolls the list to its top; whether the top showed. */
    fun top(scope: RunScope, clock: String): Boolean = go(scope, clock, emptyList(), null, UP) { false } == Walk.ENDED

    private fun go(scope: RunScope, clock: String, forms: List<String>, className: String?, swipe: String, found: (Page) -> Boolean): Walk {
        var page = read(scope, clock, forms, className) ?: return Walk.ELSEWHERE
        if (found(page)) return Walk.FOUND
        if (!page.scrolls) return Walk.ENDED
        var still = 0
        repeat(PAGES) {
            if (page.first == null) return Walk.UNKNOWN
            // A lost answer is read back by the next page.
            scope.device.once(swipe)
            scope.clock.sleep(1.seconds)
            val next = read(scope, clock, forms, className) ?: return Walk.ELSEWHERE
            if (found(next)) return Walk.FOUND
            still = if (next == page) still + 1 else 0
            if (still == 2) return Walk.ENDED
            page = next
        }
        return Walk.UNKNOWN
    }

    /** The page in view, or null when the clock app is not in front. */
    private fun read(scope: RunScope, clock: String, forms: List<String>, className: String?): Page? {
        val answers = forms.associateWith { scope.hooks.screenHas(packageName = clock, contains = it, className = className) }
        val list = scope.hooks.screenHas(packageName = clock, state = "scrollable")
        if ((answers.values + list).any { it["app"] != clock }) return null
        return Page(answers.mapValues { it.value.int("count") }, list["first"]?.takeUnless { it == "none" }, list.int("count") > 0)
    }
}

/**
 * alarm's time (design D8 and D20 of sub-project 6), on kestrel-api37 only ([NoOwnerAlarms]) and with Google Clock: the
 * first of 6:45, 6:40, 6:35 and 6:50 at which the clock has no alarm, in `{time}`, `{hour}` and `{minute}`. Each time is
 * counted as "6:45" and as a 24-hour clock's "06:45". A time is not free when `dumpsys alarm` shows an alarm of the clock
 * there (an enabled alarm anywhere in the list), or when any page of the clock's list of alarms shows one (also one that
 * is switched off). The list is read from its top to its end ([AlarmList]); a list whose top or end does not show fails
 * the setup rather than pick a time. The list is left at its top, with the home key. The clock's alarm clocks are
 * recorded first, so that the restores delete the test alarm with the clock's own taps when `dumpsys alarm` shows one at
 * that time, and name any other alarm that the run set, which they leave.
 */
data class ClockFree(val times: List<String> = TIMES) : Setup {
    override fun apply(scope: RunScope): SetupResult {
        if (scope.device.kind != DeviceKind.EMULATOR) return SetupResult.Untestable(NoOwnerAlarms.WHY)
        val clock = ClockApp.find(scope) ?: return SetupResult.Untestable("no app sets alarms")
        if (clock != ClockApp.GOOGLE) return SetupResult.Untestable("its clock app is not Google Clock, the only one whose test alarm the script deletes")
        val dump = ClockApp.alarms(scope) ?: return SetupResult.Untestable("dumpsys alarm gives no clocks")
        scope.notes[ClockApp.NOTE] = clock
        scope.notes[CLOCKS_BEFORE] = AlarmCleanup.clocks(dump, clock).joinToString(",")
        val setBefore = times.filter { time -> numbers(time).let { (hour, minute) -> AlarmCleanup.set(scope, clock, hour, minute) } }
        scope.notes[SET_BEFORE] = setBefore.joinToString(",")
        val unset = times - setBefore.toSet()
        scope.restores.push("the home screen in front", "Press Home.") { scope.device.idempotent("input keyevent KEYCODE_HOME") }
        scope.restores.change("the clock's alarms shown", "Press Home.") { scope.device.idempotent("am start -W -a android.intent.action.SHOW_ALARMS -p $clock") }
        scope.clock.sleep(3.seconds)
        val forms = unset.flatMap { time -> AlarmList.forms(time).map { it to time } }.toMap()
        val shown = mutableSetOf<String>()
        val walk = AlarmList.walk(scope, clock, forms.keys.toList()) { page ->
            page.counts.filterValues { it > 0 }.keys.forEach { shown.add(forms.getValue(it)) }
            false
        }
        if (walk == AlarmList.Walk.ELSEWHERE) {
            scope.device.idempotent("input keyevent KEYCODE_HOME")
            return SetupResult.Untestable("the clock app's alarms did not show")
        }
        if (walk != AlarmList.Walk.ENDED) {
            throw SetupFailed("The clock's list of alarms showed no top and end within ${AlarmList.PAGES} swipes, so no test time is known to be free.")
        }
        AlarmList.top(scope, clock)
        scope.device.idempotent("input keyevent KEYCODE_HOME")
        val free = unset.firstOrNull { it !in shown } ?: return SetupResult.Untestable("the clock app has an alarm at each of the test's times")
        val (hour, minute) = numbers(free)
        scope.values.time = free
        scope.values.hour = "$hour"
        scope.values.minute = "$minute"
        scope.restores.push("alarms that the run set at other times", "Clock > Alarms: check the alarms at the times that the failure names, and delete those that the run set.") {
            AlarmCleanup.others(scope, clock, hour, minute)
        }
        scope.restores.push("the test alarm at $free", "Clock > Alarms: delete the alarm at $free.") {
            AlarmCleanup.run(scope, clock, hour, minute, free)
        }
        return SetupResult.Ready
    }

    private fun numbers(time: String): List<Int> = time.split(':').map { it.toInt() }

    companion object {
        val TIMES = listOf("6:45", "6:40", "6:35", "6:50")

        /** The key of [RunScope.notes] that holds the candidate times at which `dumpsys alarm` showed an alarm before the run. */
        const val SET_BEFORE = "alarms-set-before"

        /** The key of [RunScope.notes] that holds the times of the clock's alarm clocks in `dumpsys alarm` before the run. */
        const val CLOCKS_BEFORE = "alarm-clocks-before"
    }
}

/**
 * The test alarm's deletion by Google Clock's own taps, checked in `dumpsys alarm` (design D9 of sub-project 6), and the
 * check for other alarms that the run set. Only the one card whose label holds the test's time, in one of its two forms,
 * is touched, and only when the list shows exactly one such card: a tap on the card (not on its time, which opens a time
 * picker, nor on its switch) opens the alarm's editor sheet, and the sheet's Delete is tapped only when the sheet shows
 * the test's time. Anything else taps nothing more and fails the restore, which names what to do by hand. Only alarm
 * clocks count, never the clock app's other wakeups.
 */
internal object AlarmCleanup {
    /** Deletes the test alarm at [time] when it is set. */
    fun run(scope: RunScope, clock: String, hour: Int, minute: Int, time: String) {
        check(scope.device.kind == DeviceKind.EMULATOR) { "The test alarm at $time is on ${scope.device.kind.label}, where the script deletes no alarm." }
        if (!set(scope, clock, hour, minute)) return
        scope.device.idempotent("am start -W -a android.intent.action.SHOW_ALARMS -p $clock")
        scope.clock.sleep(3.seconds)
        try {
            delete(scope, clock, time)
        } finally {
            scope.device.idempotent("input keyevent KEYCODE_HOME")
        }
        check(!set(scope, clock, hour, minute)) { "The test alarm at $time is still set in $clock." }
    }

    /** Fails, naming their times, when the clock has alarm clocks that were not there before the run, other than the test alarm; it never deletes them. */
    fun others(scope: RunScope, clock: String, hour: Int, minute: Int) {
        val dump = checkNotNull(ClockApp.alarms(scope)) { "dumpsys alarm gives no clocks." }
        val zone = ClockApp.zone(scope)
        val before = scope.notes[ClockFree.CLOCKS_BEFORE].orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()
        val next = Alarms.nextAt(dump.nowRtc, zone, hour, minute)
        val new = clocks(dump, clock).filter { at -> at !in before && listOf(next, next + DAY).none { abs(at - it) < MINUTE } }.distinct().sorted()
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone)
        check(new.isEmpty()) { "The run set ${new.size} other alarm(s) in $clock, at ${new.joinToString { format.format(Instant.ofEpochMilli(it)) }}; they stay." }
    }

    /** The times of [clock]'s alarm clocks in [dump], never its other wakeups. */
    fun clocks(dump: AlarmDump, clock: String): List<Long> = dump.of(clock).filter { it.clock }.map { it.origWhen }

    /** Whether `dumpsys alarm` shows an alarm clock of [clock] at the next [hour]:[minute], or at the same time a day later. */
    fun set(scope: RunScope, clock: String, hour: Int, minute: Int): Boolean {
        val dump = checkNotNull(ClockApp.alarms(scope)) { "dumpsys alarm gives no clocks." }
        val next = Alarms.nextAt(dump.nowRtc, ClockApp.zone(scope), hour, minute)
        return clocks(dump, clock).any { at -> listOf(next, next + DAY).any { abs(at - it) < MINUTE } }
    }

    private fun delete(scope: RunScope, clock: String, time: String): Boolean {
        var form: String? = null
        var several = false
        val walk = AlarmList.walk(scope, clock, AlarmList.forms(time), ClockApp.CARD) { page ->
            val total = page.counts.values.sum()
            several = total > 1
            form = page.counts.entries.firstOrNull { it.value == 1 }?.key
            total > 0
        }
        val card = form
        if (walk != AlarmList.Walk.FOUND || several || card == null) return false
        val taps = AppTaps(scope, clock)
        if (!taps.run(listOf(listOf(TapCandidate(contains = card, className = ClockApp.CARD))))) return false
        return sheet(scope, clock, card) && taps.run(listOf(listOf(TapCandidate(label = ClockApp.DELETE))))
    }

    /**
     * Whether the alarm's editor sheet is open on [form]: no card shows the time any more, because the sheet covers the
     * list, the sheet shows it, and it has one Delete and one Save.
     */
    private fun sheet(scope: RunScope, clock: String, form: String): Boolean {
        val card = scope.hooks.screenHas(packageName = clock, contains = form, className = ClockApp.CARD)
        val shown = scope.hooks.screenHas(packageName = clock, contains = form)
        val buttons = listOf(ClockApp.DELETE, ClockApp.SAVE).map { scope.hooks.screenHas(packageName = clock, label = it) }
        if ((buttons + card + shown).any { it["app"] != clock }) return false
        return card.int("count") == 0 && shown.int("count") >= 1 && buttons.all { it.int("count") == 1 }
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
