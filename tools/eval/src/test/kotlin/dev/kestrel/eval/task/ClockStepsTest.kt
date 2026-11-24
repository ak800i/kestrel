package dev.kestrel.eval.task

import dev.kestrel.eval.device.AlarmEntry
import dev.kestrel.eval.device.Alarms
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A device's clock app for the tests: its package, `dumpsys alarm` at noon of 2026-11-23 in Belgrade with [alarms], the
 * labels that its screen shows once ([shown]), those that its list shows only after a swipe ([below]), the hash of the
 * list's first row, which a swipe changes once, the app in front, and its ongoing notification.
 */
class ClockPhone(private val run: FakeRun, val clock: String = ClockApp.GOOGLE) {
    val alarms = mutableListOf<String>()
    val shown = mutableSetOf<String>()
    val below = mutableSetOf<String>()
    var scrolled = false
    var front = clock
    var ongoing = false
    private val zone = ZoneId.of("Europe/Belgrade")
    private val noon = at(23, 12, 0)

    init {
        run.adb.props["persist.sys.timezone"] = "Europe/Belgrade"
        run.adb.on("^cmd package resolve-activity --brief -a android.intent.action.SET_ALARM$") {
            "priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true\n$clock/com.android.deskclock.HandleApiCalls"
        }
        run.adb.on("^dumpsys alarm$") { (listOf("  nowRTC=$noon=2026-11-23 12:00:00.000 nowELAPSED=$ELAPSED") + alarms).joinToString("\n") }
        run.adb.on("^dumpsys notification$") { if (ongoing) "  NotificationRecord(0x0a1: pkg=$clock user=UserHandle{0} id=1)\n    flags=0x62" else "" }
        run.adb.on("^input swipe ") {
            scrolled = true
            ""
        }
        run.adb.hook("SCREEN_HAS") { extras ->
            val name = extras["label"] ?: extras["contains"]
            when {
                extras["state"] == "scrollable" -> "count=1 first=${if (scrolled) "b2" else "a1"} app=$front"
                name in shown || (scrolled && name in below) -> "count=1 app=$front"
                else -> "count=0 app=$front"
            }
        }
    }

    fun at(day: Int, hour: Int, minute: Int): Long = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** An alarm clock of [packageName] at [hour]:[minute] on 2026-11-[day]. */
    fun alarmAt(hour: Int, minute: Int, day: Int = 24, packageName: String = clock) {
        val time = at(day, hour, minute)
        alarms.add("    RTC_WAKEUP #0: Alarm{5a1 type 0 origWhen $time whenElapsed ${ELAPSED + time - noon} $packageName}")
    }

    /** A timer's alarm of the clock app due [seconds] later. */
    fun timerIn(seconds: Long) {
        alarms.add("    ELAPSED_WAKEUP #0: Alarm{7c3 type 2 origWhen ${ELAPSED + seconds * 1000} whenElapsed ${ELAPSED + seconds * 1000} $clock}")
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
        phone.shown.add("6:45")

        assertEquals(SetupResult.Ready, ClockFree().apply(run.scope))
        assertEquals("6:40", run.scope.values.time)
        assertEquals("6", run.scope.values.hour)
        assertEquals("40", run.scope.values.minute)
        assertEquals(ClockApp.GOOGLE, run.scope.notes[ClockApp.NOTE])
        assertTrue(run.adb.commands.contains("am start -W -a android.intent.action.SHOW_ALARMS -p com.google.android.deskclock"))
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(run.adb.commands.none { it.startsWith("am force-stop") })

        val full = FakeRun()
        ClockPhone(full).shown.addAll(ClockFree.TIMES)
        assertTrue(ClockFree().apply(full.scope) is SetupResult.Untestable)
        val elsewhere = FakeRun()
        ClockPhone(elsewhere).front = "com.google.android.apps.nexuslauncher"
        assertTrue(ClockFree().apply(elsewhere.scope) is SetupResult.Untestable)
    }

    @Test
    fun aTimeIsNotFreeWhenDumpsysShowsAnAlarmThereOrAnyPageOfTheListShowsOne() {
        val run = FakeRun(DeviceKind.A71)
        val phone = ClockPhone(run, ClockApp.SAMSUNG)
        // An enabled alarm of the owner's at 6:45 that the list's first screen does not show, and one at 6:40 further down.
        phone.alarmAt(6, 45)
        phone.below.add("6:40")

        assertEquals(SetupResult.Ready, ClockFree().apply(run.scope))
        assertEquals("6:35", run.scope.values.time)
        assertEquals("6:45", run.scope.notes[ClockFree.SET_BEFORE])
        assertEquals(2, run.adb.commands.count { it.startsWith("input swipe ") })
        run.adb.hook("SCREEN_TAP") { "tapped=true count=1" }
        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(1, phone.alarms.size)
        assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
    }

    @Test
    fun aTwentyFourHourClocksTimeWithItsLeadingZeroCountsAndIsDeleted() {
        val run = FakeRun(DeviceKind.A71)
        val phone = ClockPhone(run, ClockApp.SAMSUNG)
        // A switched-off alarm of the owner's, which dumpsys alarm does not show, on a 24-hour clock.
        phone.shown.add("06:45")

        assertEquals(SetupResult.Ready, ClockFree().apply(run.scope))
        assertEquals("6:40", run.scope.values.time)
        phone.alarmAt(6, 40)
        phone.shown.addAll(listOf("06:40", "Delete"))
        run.adb.hook("SCREEN_TAP") { extras ->
            if (extras["label"] == "Delete") phone.alarms.clear()
            "tapped=true count=1"
        }
        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(phone.alarms.isEmpty())
        assertEquals(mapOf("contains" to "06:40", "long" to "true", "package" to ClockApp.SAMSUNG), run.adb.hookCalls("SCREEN_TAP").first())
    }

    @Test
    fun theTestAlarmIsCheckedAtTheNextTimeAndDeletedByTheClocksOwnTaps() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        ClockFree().apply(run.scope)

        assertFalse(NextAlarmAt.passes(run.scope, none))
        phone.alarmAt(6, 45, packageName = "com.example.other")
        phone.alarmAt(6, 40)
        assertFalse(NextAlarmAt.passes(run.scope, none))
        phone.alarmAt(6, 45)
        assertTrue(NextAlarmAt.passes(run.scope, none))

        phone.shown.addAll(listOf("Expand alarm", "Delete"))
        val test = "origWhen ${phone.at(24, 6, 45)} "
        run.adb.hook("SCREEN_TAP") { extras ->
            if (extras["label"] == "Delete") phone.alarms.removeIf { it.contains(test) && it.endsWith("${ClockApp.GOOGLE}}") }
            "tapped=true count=1"
        }
        assertTrue(run.restores.runAll().isEmpty())
        assertFalse(NextAlarmAt.passes(run.scope, none))
        assertEquals(2, phone.alarms.size)
        assertEquals(
            listOf(mapOf("label" to "Expand alarm", "package" to ClockApp.GOOGLE), mapOf("label" to "Delete", "package" to ClockApp.GOOGLE)),
            run.adb.hookCalls("SCREEN_TAP"),
        )
    }

    @Test
    fun samsungClocksAlarmIsLongPressedAndAnAlarmThatStaysIsNamedByHand() {
        val run = FakeRun(DeviceKind.A71)
        val phone = ClockPhone(run, ClockApp.SAMSUNG)
        ClockFree().apply(run.scope)
        phone.alarmAt(6, 45)
        phone.shown.addAll(listOf("6:45", "Delete"))
        run.adb.hook("SCREEN_TAP") { "tapped=true count=1" }

        val failures = run.restores.runAll()
        assertEquals(listOf("the test alarm at 6:45"), failures.map { it.what })
        assertTrue(failures.single().reason, failures.single().reason.contains("still set"))
        assertEquals(mapOf("contains" to "6:45", "long" to "true", "package" to ClockApp.SAMSUNG), run.adb.hookCalls("SCREEN_TAP").first())
    }

    @Test
    fun appTapsPassOverAStepThatTheScreenDoesNotNeedAndNeverTapOneOfSeveral() {
        val run = FakeRun()
        var deletes = 1
        run.adb.hook("SCREEN_HAS") { extras -> "count=${if (extras["label"] == "Delete") deletes else 0} app=${ClockApp.GOOGLE}" }
        run.adb.hook("SCREEN_TAP") { "tapped=true count=1" }
        val taps = AppTaps(run.scope, ClockApp.GOOGLE)

        assertTrue(taps.run(ClockApp.alarmTaps(ClockApp.GOOGLE)))
        assertEquals(listOf(mapOf("label" to "Delete", "package" to ClockApp.GOOGLE)), run.adb.hookCalls("SCREEN_TAP"))
        deletes = 2
        assertFalse(taps.run(ClockApp.alarmTaps(ClockApp.GOOGLE)))
        assertEquals(1, run.adb.hookCalls("SCREEN_TAP").size)
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

        phone.shown.add("Delete")
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
