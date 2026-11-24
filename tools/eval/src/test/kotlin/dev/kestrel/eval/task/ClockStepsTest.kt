package dev.kestrel.eval.task

import dev.kestrel.eval.device.AlarmEntry
import dev.kestrel.eval.device.Alarms
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A device's clock app for the tests, modelled on Google Clock: its package, `dumpsys alarm` at noon of 2026-11-23 in
 * Belgrade with [alarms], the [rows] of its list of alarms, each on a page of the list from its top, the page in view,
 * which a swipe changes, the hash of the list's first row in view, the app in front, its running timers and its ongoing
 * notification. A tap on a time expands the first row that holds it and collapses the others, as screen-tap picks the
 * first element whose label holds the text; Delete deletes the expanded row and its alarm, or on the timers page the
 * running timer.
 */
class ClockPhone(private val run: FakeRun, val clock: String = ClockApp.GOOGLE) {
    /** A row of the list that shows [time]; [at] is its alarm's time in `dumpsys alarm` while it is switched on. */
    class Row(val time: String, val page: Int, var expanded: Boolean = false, val at: Long? = null)

    val alarms = mutableListOf<String>()
    val rows = mutableListOf<Row>()
    private val timers = mutableListOf<String>()
    var position = 0
    var swipes = 0

    /** Whether the list scrolls at all, whether every swipe shows new rows (a list without an end), and whether its first row has no name. */
    var scrollable = true
    var endless = false
    var unnamed = false

    /** Whether a tap on a time leaves its row as it was, as when Google Clock opens its time picker instead. */
    var inert = false
    var front = clock
    var ongoing = false
    private var screen = "alarms"
    private val zone = ZoneId.of("Europe/Belgrade")
    private val noon = at(23, 12, 0)

    init {
        run.adb.props["persist.sys.timezone"] = "Europe/Belgrade"
        run.adb.on("^cmd package resolve-activity --brief -a android.intent.action.SET_ALARM$") {
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n$clock/com.android.deskclock.HandleApiCalls"
        }
        run.adb.on("^dumpsys alarm$") { (listOf("  nowRTC=$noon=2026-11-23 12:00:00.000 nowELAPSED=$ELAPSED") + alarms).joinToString("\n") }
        run.adb.on("^dumpsys notification$") { if (ongoing) "  NotificationRecord(0x0a1: pkg=$clock user=UserHandle{0} id=1)\n    flags=0x62" else "" }
        run.adb.on("^am start -W -a android.intent.action.SHOW_ALARMS ") {
            screen = "alarms"
            ""
        }
        run.adb.on("^am start -W -a android.intent.action.SHOW_TIMERS ") {
            screen = "timers"
            ""
        }
        run.adb.on("^input swipe 540 1700 540 700 ") {
            swipes++
            position = minOf(position + 1, rows.maxOfOrNull { it.page } ?: 0)
            ""
        }
        run.adb.on("^input swipe 540 700 540 1700 ") {
            swipes++
            position = maxOf(position - 1, 0)
            ""
        }
        run.adb.hook("SCREEN_HAS") { has(it) }
        run.adb.hook("SCREEN_TAP") { tap(it) }
    }

    fun at(day: Int, hour: Int, minute: Int): Long = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** A row of the list at [time], switched off, as the owner's or Google Clock's own alarms of 8:30 and 9:00. */
    fun row(time: String, page: Int = 0, expanded: Boolean = false) {
        rows.add(Row(time, page, expanded))
    }

    /** An alarm clock of [packageName] at [hour]:[minute] on 2026-11-[day] in `dumpsys alarm`, without a row. */
    fun alarmAt(hour: Int, minute: Int, day: Int = 24, packageName: String = clock): Long {
        val time = at(day, hour, minute)
        alarms.add("    RTC_WAKEUP #0: Alarm{5a1 type 0 origWhen $time whenElapsed ${ELAPSED + time - noon} $packageName}")
        return time
    }

    /** An alarm that is switched on at [hour]:[minute] tomorrow, with its row, which shows [time]. */
    fun setAlarm(hour: Int, minute: Int, time: String = "$hour:%02d".format(minute), page: Int = 0) {
        rows.add(Row(time, page, at = alarmAt(hour, minute)))
    }

    /** A timer's alarm of the clock app due [seconds] later. */
    fun timerIn(seconds: Long) {
        val line = "    ELAPSED_WAKEUP #0: Alarm{7c3 type 2 origWhen ${ELAPSED + seconds * 1000} whenElapsed ${ELAPSED + seconds * 1000} $clock}"
        alarms.add(line)
        timers.add(line)
    }

    private fun here(): List<Row> = rows.filter { it.page == position }

    private fun has(extras: Map<String, String>): String {
        if (extras["state"] == "scrollable") {
            val first = when {
                !scrollable || unnamed -> "none"
                endless -> "s$swipes"
                else -> "p$position"
            }
            return "count=${if (scrollable) 1 else 0} first=$first app=$front"
        }
        val name = extras["label"] ?: extras["contains"]
        val here = here()
        fun top(row: Row) = 300 + 300 * here.indexOf(row)
        val bounds = when {
            screen == "timers" -> if (name == "Delete") timers.map { "0,900,300,980" } else emptyList()
            name == "Collapse alarm" -> here.filter { it.expanded }.map { "900,${top(it)},1000,${top(it) + 100}" }
            name == "Expand alarm" -> here.filterNot { it.expanded }.map { "900,${top(it)},1000,${top(it) + 100}" }
            name == "Delete" -> here.filter { it.expanded }.map { "0,${top(it) + 200},300,${top(it) + 280}" }
            else -> here.filter { it.time == name }.map { "0,${top(it)},700,${top(it) + 100}" }
        }
        return "count=${bounds.size} bounds=${bounds.firstOrNull() ?: "none"} app=$front"
    }

    private fun tap(extras: Map<String, String>): String {
        val contains = extras["contains"]
        val targets = when {
            screen == "timers" -> if (extras["label"] == "Delete") timers.toList() else emptyList()
            extras["label"] == "Delete" -> here().filter { it.expanded }
            contains != null -> here().filter { it.time.contains(contains, ignoreCase = true) }
            else -> emptyList()
        }
        when (val target = targets.firstOrNull()) {
            null -> Unit
            is String -> {
                alarms.remove(target)
                timers.remove(target)
                ongoing = false
            }
            is Row -> if (extras["label"] == "Delete") {
                rows.remove(target)
                target.at?.let { at -> alarms.removeIf { it.contains("origWhen $at ") && it.endsWith("$clock}") } }
            } else if (!inert) {
                rows.forEach { it.expanded = it === target }
            }
        }
        return "tapped=${targets.isNotEmpty()} count=${targets.size}"
    }

    companion object {
        const val ELAPSED = 1_000_000L
    }
}

/** The clock's steps and checks (design D8 and D9 of sub-project 6). */
class ClockStepsTest {
    private val none = HookAnswer.EMPTY

    @Test
    fun theAlarmsDumpGivesTheClocksAndEachAlarmAndTheNextTimeIsTodayOrTomorrow() {
        val dump = listOf(
            "  nowRTC=1795086000000=2026-11-23 12:00:00.000 nowELAPSED=1000000",
            "    RTC_WAKEUP #0: Alarm{5a1 type 0 origWhen 1795153500000 whenElapsed 68500000 com.google.android.deskclock}",
            "    ELAPSED_WAKEUP #1: Alarm{6b2 type 2 origWhen 1290000 whenElapsed 1290000 com.google.android.deskclock}",
        )
        val parsed = Alarms.parse(dump)!!

        assertEquals(1795086000000L, parsed.nowRtc)
        assertEquals(1000000L, parsed.nowElapsed)
        assertEquals(
            listOf(AlarmEntry(0, 1795153500000L, 68500000L, ClockApp.GOOGLE), AlarmEntry(2, 1290000L, 1290000L, ClockApp.GOOGLE)),
            parsed.of(ClockApp.GOOGLE),
        )
        assertNull(Alarms.parse(listOf("Current Alarm Manager state:")))
        val zone = ZoneId.of("Europe/Belgrade")
        fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(at(24, 6, 45), Alarms.nextAt(at(23, 12, 0), zone, 6, 45))
        assertEquals(at(23, 6, 45), Alarms.nextAt(at(23, 5, 0), zone, 6, 45))
    }

    @Test
    fun clockFreePicksATimeWithoutAnAlarmOnTheClocksScreenAndNeverStopsTheClock() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        phone.row("6:45")

        assertEquals(SetupResult.Ready, ClockFree().apply(run.scope))
        assertEquals("6:40", run.scope.values.time)
        assertEquals("6", run.scope.values.hour)
        assertEquals("40", run.scope.values.minute)
        assertEquals(ClockApp.GOOGLE, run.scope.notes[ClockApp.NOTE])
        assertTrue(run.adb.commands.contains("am start -W -a android.intent.action.SHOW_ALARMS -p com.google.android.deskclock"))
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(run.adb.commands.none { it.startsWith("am force-stop") })

        val full = FakeRun()
        ClockPhone(full).apply { ClockFree.TIMES.forEach { row(it) } }
        assertTrue(ClockFree().apply(full.scope) is SetupResult.Untestable)
        val elsewhere = FakeRun()
        ClockPhone(elsewhere).front = "com.google.android.apps.nexuslauncher"
        assertTrue(ClockFree().apply(elsewhere.scope) is SetupResult.Untestable)
    }

    @Test
    fun aTimeIsNotFreeWhenDumpsysShowsAnAlarmThereOrAnyPageOfTheListShowsOne() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        // An enabled alarm at 6:45 that the list's first screen does not show, and one at 6:40 further down.
        phone.alarmAt(6, 45)
        phone.row("6:40", page = 1)

        assertEquals(SetupResult.Ready, ClockFree().apply(run.scope))
        assertEquals("6:35", run.scope.values.time)
        assertEquals("6:45", run.scope.notes[ClockFree.SET_BEFORE])
        assertEquals(0, phone.position)
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(1, phone.alarms.size)
        assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
    }

    @Test
    fun theScanStartsAtTheListsTopWhereverTheListWasLeft() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        phone.row("6:45")
        phone.row("9:00", page = 1)
        phone.row("6:40", page = 2)
        phone.position = 2

        assertEquals(SetupResult.Ready, ClockFree().apply(run.scope))
        assertEquals("6:35", run.scope.values.time)
    }

    @Test
    fun aListWithoutAConfirmedEndMakesTheSetupFailRatherThanPickATime() {
        for (endless in listOf(true, false)) {
            val run = FakeRun()
            val phone = ClockPhone(run)
            phone.row("6:45")
            phone.row("6:40", page = 1)
            if (endless) phone.endless = true else phone.unnamed = true

            assertThrows(SetupFailed::class.java) { ClockFree().apply(run.scope) }
            assertNull(run.scope.values.time)
            assertTrue(run.restores.runAll().isEmpty())
        }
    }

    @Test
    fun aTwentyFourHourClocksTimeWithItsLeadingZeroCountsAndIsDeleted() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        // A switched-off alarm, which dumpsys alarm does not show, on a 24-hour clock.
        phone.row("06:45")

        assertEquals(SetupResult.Ready, ClockFree().apply(run.scope))
        assertEquals("6:40", run.scope.values.time)
        phone.setAlarm(6, 40, time = "06:40")
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(phone.alarms.isEmpty())
        assertEquals(listOf("06:45"), phone.rows.map { it.time })
        assertEquals(
            listOf(mapOf("contains" to "06:40", "package" to ClockApp.GOOGLE), mapOf("label" to "Delete", "package" to ClockApp.GOOGLE)),
            run.adb.hookCalls("SCREEN_TAP"),
        )
    }

    @Test
    fun theTestAlarmIsCheckedAtTheNextTimeAndDeletedOnlyThroughItsOwnRow() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        ClockFree().apply(run.scope)

        assertFalse(NextAlarmAt.passes(run.scope, none))
        phone.alarmAt(6, 45, packageName = "com.example.other")
        phone.alarmAt(6, 40)
        assertFalse(NextAlarmAt.passes(run.scope, none))
        phone.setAlarm(6, 45)
        assertTrue(NextAlarmAt.passes(run.scope, none))

        // Google Clock's own alarms of 8:30 and 9:00, the first left expanded with its Delete.
        phone.row("8:30", expanded = true)
        phone.row("9:00")
        val failures = run.restores.runAll()
        assertEquals(listOf("alarms that the run set at other times"), failures.map { it.what })
        assertFalse(NextAlarmAt.passes(run.scope, none))
        assertEquals(listOf("8:30", "9:00"), phone.rows.map { it.time })
        assertEquals(
            listOf(mapOf("contains" to "6:45", "package" to ClockApp.GOOGLE), mapOf("label" to "Delete", "package" to ClockApp.GOOGLE)),
            run.adb.hookCalls("SCREEN_TAP"),
        )
    }

    @Test
    fun theRestoreTapsNoDeleteThatItCannotBindToTheTestAlarmsRow() {
        fun failsAndKeeps(setUp: (ClockPhone) -> Unit, taps: List<Map<String, String>>) {
            val run = FakeRun()
            val phone = ClockPhone(run)
            ClockFree().apply(run.scope)
            setUp(phone)
            val owners = phone.rows.filter { it.at == null }.map { it.time }

            val failures = run.restores.runAll()
            assertTrue(failures.map { it.what }.toString(), "the test alarm at 6:45" in failures.map { it.what })
            assertTrue(failures.first { it.what == "the test alarm at 6:45" }.reason.contains("still set"))
            assertTrue(run.adb.hookCalls("SCREEN_TAP").none { it["label"] == "Delete" })
            assertEquals(taps, run.adb.hookCalls("SCREEN_TAP"))
            assertEquals(owners, phone.rows.filter { it.at == null }.map { it.time })
        }
        val time = listOf(mapOf("contains" to "6:45", "package" to ClockApp.GOOGLE))

        // The test alarm is set, but no row shows its time, and a row of the owner's is expanded with its Delete.
        failsAndKeeps({ phone ->
            phone.alarmAt(6, 45)
            phone.row("8:30", expanded = true)
            phone.row("9:00")
        }, emptyList())
        // Two rows show the time, in its two forms.
        failsAndKeeps({ phone ->
            phone.setAlarm(6, 45)
            phone.row("06:45")
        }, emptyList())
        // The tap on the time does not expand its row, as when it opens a time picker, and another row is expanded.
        failsAndKeeps({ phone ->
            phone.setAlarm(6, 45)
            phone.row("8:30", expanded = true)
            phone.inert = true
        }, time)
        // The tap on the time finds a second label that holds it, 16:45, so it answers a count of 2.
        failsAndKeeps({ phone ->
            phone.row("16:45")
            phone.setAlarm(6, 45)
        }, time)
    }

    @Test
    fun theRestoreScrollsToTheListsTopAndFindsTheTestAlarmOnItsPage() {
        for (page in listOf(0, 1)) {
            val run = FakeRun()
            val phone = ClockPhone(run)
            phone.row("8:30")
            phone.row("9:00", page = 1)
            ClockFree().apply(run.scope)
            phone.setAlarm(6, 45, page = page)
            // The run left the list at its end.
            phone.position = 1

            assertTrue(run.restores.runAll().isEmpty())
            assertFalse(NextAlarmAt.passes(run.scope, none))
            assertEquals(listOf("8:30", "9:00"), phone.rows.map { it.time })
        }
    }

    @Test
    fun anAlarmThatTheRunSetAtAnotherTimeIsNamedByHandAndStays() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        phone.alarmAt(7, 0)
        ClockFree().apply(run.scope)
        phone.setAlarm(6, 45)
        phone.alarmAt(18, 45, day = 23)

        val failures = run.restores.runAll()
        assertEquals(listOf("alarms that the run set at other times"), failures.map { it.what })
        assertTrue(failures.single().reason, failures.single().reason.contains("2026-11-23 18:45"))
        assertFalse(failures.single().reason, failures.single().reason.contains("07:00"))
        assertEquals(2, phone.alarms.size)
        assertTrue(run.adb.hookCalls("SCREEN_TAP").none { it["contains"] == "18:45" })
    }

    @Test
    fun appTapsNeverPassOverAStepAndStopAtATapThatDoesNotCountOne() {
        val steps = listOf(listOf(TapCandidate(label = "Select")), listOf(TapCandidate(label = "Delete")))
        fun taps(has: (Map<String, String>) -> Int, tap: String, lose: Boolean = false): Pair<Boolean, Int> {
            val run = FakeRun()
            run.adb.hook("SCREEN_HAS") { "count=${has(it)} app=${ClockApp.GOOGLE}" }
            run.adb.hook("SCREEN_TAP") { tap }
            if (lose) run.adb.loseNext("SCREEN_TAP")
            return AppTaps(run.scope, ClockApp.GOOGLE).run(steps) to run.adb.hookCalls("SCREEN_TAP").size
        }

        assertEquals(true to 2, taps({ 1 }, "tapped=true count=1"))
        // A step that the screen does not show ends the taps, never with a lone Delete.
        assertEquals(false to 0, taps({ if (it["label"] == "Delete") 1 else 0 }, "tapped=true count=1"))
        assertEquals(false to 0, taps({ if (it["label"] == "Delete") 1 else 2 }, "tapped=true count=1"))
        // A tap that found several elements, or whose answer was lost, ends them too.
        assertEquals(false to 1, taps({ 1 }, "tapped=true count=2"))
        assertEquals(false to 1, taps({ 1 }, "tapped=true count=1", lose = true))
    }

    @Test
    fun aNewTimerDueInFourToFiveMinutesWithItsNotificationPassesAndIsDeletedAfterTheRun() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        phone.timerIn(270)

        assertEquals(SetupResult.Ready, TimerCleanup.apply(run.scope))
        assertFalse(TimerRunning.passes(run.scope, none))
        phone.ongoing = true
        assertFalse(TimerRunning.passes(run.scope, none))
        phone.ongoing = false
        phone.timerIn(290)
        assertFalse(TimerRunning.passes(run.scope, none))
        phone.ongoing = true
        assertTrue(TimerRunning.passes(run.scope, none))

        run.adb.hook("SCREEN_HAS") { extras -> "count=${if (extras["label"] == "Delete") 1 else 0} app=${ClockApp.GOOGLE}" }
        run.adb.hook("SCREEN_TAP") {
            phone.alarms.removeAt(phone.alarms.lastIndex)
            phone.ongoing = false
            "tapped=true count=1"
        }
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(1, phone.alarms.size)
        assertTrue(run.adb.commands.contains("am start -W -a android.intent.action.SHOW_TIMERS -p com.google.android.deskclock"))
        assertTrue(run.adb.commands.none { it.startsWith("am force-stop") })
    }
}
