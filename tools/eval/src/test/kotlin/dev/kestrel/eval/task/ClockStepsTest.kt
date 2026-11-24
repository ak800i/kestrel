package dev.kestrel.eval.task

import dev.kestrel.eval.device.AlarmEntry
import dev.kestrel.eval.device.Alarms
import dev.kestrel.eval.device.DeviceKind
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
 * A device's clock app for the tests, modelled on Google Clock on kestrel-api37: its package, `dumpsys alarm` at noon of
 * 2026-11-23 in Belgrade with [alarms], the [rows] of its list of alarms, each on a page of the list from its top, the
 * page in view, which a swipe changes, the hash of the list's first row in view, the app in front, its running timers
 * and its ongoing notification. Each row is a card whose label holds its time ("Alarm Tomorrow 6:45 AM Alarm is
 * currently enabled."), with the time and the switch inside it, which hold the time too. The hooks match them as
 * [FakeScreen] says: a tap on a card opens the alarm's editor sheet, which covers the list and shows the time, Delete and
 * Save; the sheet's Delete deletes the alarm and its row. A tap on the time or the switch changes nothing here. On the
 * timers page, Delete deletes the running timer.
 */
class ClockPhone(private val run: FakeRun, val clock: String = ClockApp.GOOGLE) {
    /** A row of the list that shows [time]; [at] is its alarm's time in `dumpsys alarm` while it is switched on. */
    class Row(val time: String, val page: Int, val at: Long? = null)

    private class Element(val label: String, val className: String, val bounds: String, val row: Row?, val delete: Boolean = false)

    val alarms = mutableListOf<String>()
    val rows = mutableListOf<Row>()
    private val timers = mutableListOf<String>()
    var position = 0
    var swipes = 0

    /** Whether the list scrolls at all, whether every swipe shows new rows (a list without an end), and whether its first row has no name. */
    var scrollable = true
    var endless = false
    var unnamed = false

    /** Whether a tap on a card leaves the list as it was, and whether it opens the sheet of the list's first row instead of its own. */
    var inert = false
    var wrongSheet = false
    var front = clock
    var ongoing = false

    /** Whether One UI's tip about the full screen has the focus and covers the screen, with its one button, OK. */
    var tip = false

    /** The row whose editor sheet is open, or null on the list. */
    var sheet: Row? = null
    private var screen = "alarms"
    private val zone = ZoneId.of("Europe/Belgrade")
    private val noon = at(23, 12, 0)

    init {
        run.adb.props["persist.sys.timezone"] = "Europe/Belgrade"
        run.adb.on("^cmd package resolve-activity --brief -a android.intent.action.SET_ALARM$") {
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n$clock/com.android.deskclock.HandleApiCalls"
        }
        run.adb.on("^dumpsys alarm$") { (listOf("  nowRTC=$noon=2026-11-23 12:00:00.000 nowELAPSED=$ELAPSED") + alarms).joinToString("\n") }
        run.adb.on("^dumpsys notification$") {
            if (ongoing) "  NotificationRecord(0x0a1: pkg=$clock user=UserHandle{0} id=1)\n    flags=ONGOING_EVENT|LOCAL_ONLY|PROMOTED_ONGOING" else ""
        }
        run.adb.on("^am start -W -a android.intent.action.SHOW_ALARMS ") {
            screen = "alarms"
            sheet = null
            ""
        }
        run.adb.on("^am start -W -a android.intent.action.SHOW_TIMERS ") {
            screen = "timers"
            ""
        }
        run.adb.on("^dumpsys window$") { "  mCurrentFocus=Window{5999a62 u0 ${if (tip) "ImmersiveModeConfirmation" else "$clock/.Main"}}" }
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
    fun row(time: String, page: Int = 0) {
        rows.add(Row(time, page))
    }

    /** An alarm clock of [packageName] at [hour]:[minute] on 2026-11-[day] in `dumpsys alarm`, without a row, with Google Clock's wakeups around it. */
    fun alarmAt(hour: Int, minute: Int, day: Int = 24, packageName: String = clock): Long {
        val time = at(day, hour, minute)
        alarms.add(entry(time, packageName, clock = true))
        // Google Clock's UPDATE_ALARM_INSTANCES wakeups, every 15 s after the alarm and half an hour before it, are no alarm clocks.
        for (at in listOf(time - 1_800_000L, time + 15_000L, time + 30_000L)) alarms.add(entry(at, packageName, clock = false))
        return time
    }

    /** A wakeup of [packageName] at [hour]:[minute] on 2026-11-[day] that is no alarm clock, as Google Clock's UPDATE_ALARM_INSTANCES. */
    fun wakeupAt(hour: Int, minute: Int, day: Int = 24, packageName: String = clock) {
        alarms.add(entry(at(day, hour, minute), packageName, clock = false))
    }

    private fun entry(time: Long, packageName: String, clock: Boolean): String {
        val lines = listOf(
            "    RTC_WAKEUP #0: Alarm{5a1 type 0 origWhen $time whenElapsed ${ELAPSED + time - noon} $packageName}",
            "      tag=*walarm*:com.android.deskclock.action.UPDATE_ALARM_INSTANCES",
        ) + if (clock) listOf("      Alarm clock:", "        triggerTime=2026-11-24 06:45:00.000") else emptyList()
        return lines.joinToString("\n")
    }

    /** The number of alarm clocks in `dumpsys alarm`. */
    fun clocks(): Int = alarms.count { it.contains("Alarm clock:") }

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

    private fun elements(): List<Element> {
        val open = sheet
        return when {
            tip -> listOf(Element("OK", "android.widget.Button", "424,2062,655,2162", null))
            screen == "timers" && clock == ClockApp.SAMSUNG -> timers.flatMap {
                // One UI 5.1's controls of the running timer: each a clickable container around a clickable Button of the same label.
                listOf(
                    Element("Delete", "android.view.ViewGroup", "105,1998,454,2203", null, delete = true),
                    Element("Delete", "android.widget.Button", "147,2037,454,2163", null, delete = true),
                    Element("Pause", "android.view.ViewGroup", "626,1998,975,2203", null),
                    Element("Pause", "android.widget.Button", "626,2037,933,2163", null),
                )
            }
            screen == "timers" -> timers.map { Element("Delete", "android.widget.Button", "0,900,300,980", null, delete = true) }
            open != null -> listOf(
                Element("${open.time} AM Edit", "android.view.ViewGroup", "0,1000,1080,1155", open),
                Element("${open.time} AM", "android.widget.TextView", "95,1000,394,1155", open),
                Element("Delete", "android.widget.Button", "42,2179,235,2305", open, delete = true),
                Element("Save", "android.widget.Button", "873,2179,1038,2305", open),
            )
            else -> rows.filter { it.page == position }.withIndex().flatMap { (index, row) ->
                val top = 300 + 410 * index
                listOf(
                    Element("Alarm Tomorrow ${row.time} AM Alarm is currently enabled.", "androidx.cardview.widget.CardView", "42,$top,1038,${top + 400}", row),
                    Element("${row.time} AM", "android.widget.TextView", "95,${top + 200},394,${top + 355}", row),
                    Element("${row.time} AM alarm", "android.widget.Switch", "848,${top + 215},985,${top + 341}", row),
                )
            }
        }
    }

    private fun has(extras: Map<String, String>): String {
        if (extras["state"] == "scrollable") {
            val first = when {
                !scrollable || unnamed -> "none"
                endless -> "s$swipes"
                else -> "p$position"
            }
            return "count=${if (scrollable) 1 else 0} first=$first app=$front"
        }
        val all = elements()
        val className = extras["class"]
        val found = all.filter { element ->
            FakeScreen.has(listOf(element.label), extras) == 1 && (className == null || element.className.endsWith(".$className"))
        }
        return "count=${found.size} bounds=${found.firstOrNull()?.bounds ?: "none"} app=$front"
    }

    private fun tap(extras: Map<String, String>): String {
        val all = elements()
        val matches = FakeScreen.taps(all.map { it.label }, extras)
        val target = matches.firstOrNull()?.let { all[it] } ?: return "tapped=false count=0"
        val row = target.row
        when {
            tip && target.label == "OK" -> tip = false
            target.delete && row == null -> {
                timers.removeFirstOrNull()?.let { alarms.remove(it) }
                ongoing = false
            }
            target.delete && row != null -> {
                rows.remove(row)
                row.at?.let { at ->
                    val times = listOf(at - 1_800_000L, at, at + 15_000L, at + 30_000L)
                    alarms.removeIf { line -> times.any { line.contains("origWhen $it ") } && line.contains("$clock}") }
                }
                sheet = null
            }
            row != null && sheet == null && target.className.endsWith("CardView") && !inert -> sheet = if (wrongSheet) rows.first() else row
        }
        return "tapped=true count=${matches.size}"
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
    fun onlyAlarmClocksCountNeverTheClocksOtherWakeups() {
        val dump = listOf(
            "  nowRTC=1795086000000=2026-11-23 12:00:00.000 nowELAPSED=1000000",
            "      RTC_WAKEUP #14: Alarm{33aeac9 type 0 origWhen 1795151700000 whenElapsed 66700000 com.google.android.deskclock}",
            "        tag=*walarm*:com.android.deskclock.action.UPDATE_ALARM_INSTANCES",
            "      RTC_WAKEUP #19: Alarm{e394fdf type 0 origWhen 1795153500000 whenElapsed 68500000 com.google.android.deskclock}",
            "        tag=*walarm*:com.android.deskclock.action.UPDATE_ALARM_INSTANCES",
            "        whenElapsed=+18h45m maxWhenElapsed=+18h45m",
            "        Alarm clock:",
            "          triggerTime=2026-11-24 06:45:00.000",
            "      RTC_WAKEUP #20: Alarm{85718 type 0 origWhen 1795153515000 whenElapsed 68515000 com.google.android.deskclock}",
            "        tag=*walarm*:com.android.deskclock.action.UPDATE_ALARM_INSTANCES",
            "  Next wake from idle: Alarm{e394fdf type 0 origWhen 1795153500000 whenElapsed 68500000 com.google.android.deskclock}",
            "    Alarm clock:",
            "  Alarm clock:",
        )

        assertEquals(listOf(1795153500000L), AlarmCleanup.clocks(Alarms.parse(dump)!!, ClockApp.GOOGLE).distinct())

        val run = FakeRun()
        val phone = ClockPhone(run)
        ClockFree().apply(run.scope)
        // Google Clock's wakeups at the test's time and around an alarm of the owner's that was there before the run.
        phone.wakeupAt(6, 45)
        phone.wakeupAt(4, 45)
        assertFalse(NextAlarmAt.passes(run.scope, none))
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
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
        assertEquals(1, phone.clocks())
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

        // Google Clock's own alarms of 8:30 and 9:00.
        phone.row("8:30")
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

        // The test alarm is set, but no card shows its time.
        failsAndKeeps({ phone ->
            phone.alarmAt(6, 45)
            phone.row("8:30")
            phone.row("9:00")
        }, emptyList())
        // Two cards show the time, in its two forms.
        failsAndKeeps({ phone ->
            phone.setAlarm(6, 45)
            phone.row("06:45")
        }, emptyList())
        // The tap on the card opens no sheet.
        failsAndKeeps({ phone ->
            phone.setAlarm(6, 45)
            phone.row("8:30")
            phone.inert = true
        }, time)
        // The tap on the card opens the sheet of another alarm, whose Delete is never tapped.
        failsAndKeeps({ phone ->
            phone.row("8:30")
            phone.setAlarm(6, 45)
            phone.wrongSheet = true
        }, time)
        // The tap on the card finds labels of a second card that hold it, 16:45, which comes first, so it answers another count.
        failsAndKeeps({ phone ->
            phone.row("16:45")
            phone.setAlarm(6, 45)
        }, time)
    }

    @Test
    fun theRestoreDeletesEveryCardThatTheRunSetAtTheTestTimeAlsoASwitchedOffOne() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        phone.row("8:30")
        ClockFree().apply(run.scope)
        // A live run on kestrel-api37: the one-time alarm, and an alarm on Wednesdays that the model switched off, both at 6:45.
        phone.setAlarm(6, 45)
        phone.row("6:45")

        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf("8:30"), phone.rows.map { it.time })
        assertEquals(0, phone.clocks())
        val card = mapOf("contains" to "6:45", "package" to ClockApp.GOOGLE)
        val delete = mapOf("label" to "Delete", "package" to ClockApp.GOOGLE)
        assertEquals(listOf(card, delete, card, delete), run.adb.hookCalls("SCREEN_TAP"))

        // The run's only alarm switched off: dumpsys alarm shows none, and its card is deleted all the same.
        val off = FakeRun()
        val offPhone = ClockPhone(off)
        ClockFree().apply(off.scope)
        offPhone.row("6:45")
        assertTrue(off.restores.runAll().isEmpty())
        assertTrue(offPhone.rows.isEmpty())
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
        assertEquals(2, phone.clocks())
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
        for (clock in listOf(ClockApp.GOOGLE, ClockApp.SAMSUNG)) {
            val run = FakeRun(if (clock == ClockApp.SAMSUNG) DeviceKind.A71 else DeviceKind.EMULATOR)
            val phone = ClockPhone(run, clock)

            assertEquals(clock, SetupResult.Ready, TimerCleanup.apply(run.scope))
            assertFalse(clock, TimerRunning.passes(run.scope, none))
            phone.timerIn(290)
            assertFalse(clock, TimerRunning.passes(run.scope, none))
            phone.ongoing = true
            assertTrue(clock, TimerRunning.passes(run.scope, none))
            // On the A71, One UI's tip about the full screen may cover the timers page; it is dismissed with its own OK.
            phone.tip = clock == ClockApp.SAMSUNG

            assertTrue(clock, run.restores.runAll().isEmpty())
            assertTrue(clock, phone.alarms.isEmpty())
            assertFalse(clock, phone.tip)
            val taps = if (clock == ClockApp.SAMSUNG) listOf("OK", "Delete") else listOf("Delete")
            assertEquals(clock, taps, run.adb.hookCalls("SCREEN_TAP").map { it["label"] })
            assertTrue(clock, run.adb.commands.contains("am start -W -a android.intent.action.SHOW_TIMERS -p $clock"))
            assertTrue(clock, run.adb.commands.none { it.startsWith("am force-stop") })
        }
    }

    @Test
    fun aTimerOfTheOwnersMakesTheTaskUntestableAndAnotherTimerAfterTheRunIsLeftToTheOwner() {
        fun phone(): Pair<FakeRun, ClockPhone> = FakeRun(DeviceKind.A71).let { it to ClockPhone(it, ClockApp.SAMSUNG) }

        // A running timer of the owner's, an hour away, and a paused one, which only its notification shows.
        val (running, owners) = phone()
        owners.timerIn(3_600)
        val (paused, pausedOwners) = phone()
        pausedOwners.ongoing = true
        for (run in listOf(running, paused)) {
            assertTrue(TimerCleanup.apply(run.scope) is SetupResult.Untestable)
            assertTrue(run.restores.runAll().isEmpty())
            assertTrue(run.adb.commands.none { it.startsWith("am start") })
        }

        // A timer that the owner started during the run, whether it ends before the test timer or long after it.
        for (other in listOf(120L, 3_600L)) {
            val (run, clock) = phone()
            assertEquals(SetupResult.Ready, TimerCleanup.apply(run.scope))
            clock.timerIn(290)
            clock.timerIn(other)
            clock.ongoing = true
            val failure = run.restores.runAll().single()
            assertTrue(failure.reason, failure.reason.contains("The clock runs 2 timers, so the script deleted none of them."))
            assertEquals(2, clock.alarms.size)
            assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
            assertTrue(run.adb.commands.none { it.contains("SHOW_TIMERS") })
        }
    }
}
