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
 * 2026-11-23 in Belgrade with [alarms], the app in front, its running timers and its notifications: a running timer's,
 * ongoing or ([localOnly]) as a cleared Google Clock posts it, and another as an upcoming alarm's. On the timers page,
 * Delete deletes the running timer. `pm clear` of the clock deletes its alarms, its timers and its notifications, unless
 * it is [stuck].
 */
class ClockPhone(private val run: FakeRun, val clock: String = ClockApp.GOOGLE) {
    private class Element(val label: String, val className: String, val bounds: String, val delete: Boolean = false)

    val alarms = mutableListOf<String>()
    private val timers = mutableListOf<String>()
    var front = clock
    var ongoing = false
    var notified = false

    /** Whether a running timer's notification is shown as a cleared Google Clock 9.2 posts it: LOCAL_ONLY, not ongoing. */
    var localOnly = false

    /** Whether `pm clear` of the clock answers Success but leaves its alarms. */
    var stuck = false

    /** Whether One UI's tip about the full screen has the focus and covers the screen, with its one button, OK. */
    var tip = false

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
            listOfNotNull(
                if (ongoing) "  NotificationRecord(0x0a1: pkg=$clock user=UserHandle{0} id=1)\n    flags=ONGOING_EVENT|LOCAL_ONLY|PROMOTED_ONGOING" else null,
                if (notified) "  NotificationRecord(0x0a2: pkg=$clock user=UserHandle{0} id=2)\n    flags=AUTO_CANCEL" else null,
                if (localOnly) "  NotificationRecord(0x0a3: pkg=$clock user=UserHandle{0} id=2147483641)\n    flags=LOCAL_ONLY" else null,
            ).joinToString("\n")
        }
        run.adb.on("^am start -W -a android.intent.action.SHOW_TIMERS ") {
            screen = "timers"
            ""
        }
        run.adb.on("^pm clear $clock$") {
            if (!stuck) {
                alarms.clear()
                timers.clear()
            }
            ongoing = false
            notified = false
            localOnly = false
            "Success"
        }
        run.adb.on("^dumpsys window$") { "  mCurrentFocus=Window{5999a62 u0 ${if (tip) "ImmersiveModeConfirmation" else "$clock/.Main"}}" }
        run.adb.hook("SCREEN_HAS") { has(it) }
        run.adb.hook("SCREEN_TAP") { tap(it) }
    }

    fun at(day: Int, hour: Int, minute: Int): Long = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    /** An alarm clock of [packageName] at [hour]:[minute] on 2026-11-[day] in `dumpsys alarm`, with Google Clock's wakeups around it. */
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

    /** An alarm that is switched on at [hour]:[minute] tomorrow. */
    fun setAlarm(hour: Int, minute: Int) {
        alarmAt(hour, minute)
    }

    /** A timer's alarm of the clock app due [seconds] later. */
    fun timerIn(seconds: Long) {
        val line = "    ELAPSED_WAKEUP #0: Alarm{7c3 type 2 origWhen ${ELAPSED + seconds * 1000} whenElapsed ${ELAPSED + seconds * 1000} $clock}"
        alarms.add(line)
        timers.add(line)
    }

    private fun elements(): List<Element> = when {
        tip -> listOf(Element("OK", "android.widget.Button", "424,2062,655,2162"))
        screen == "timers" && clock == ClockApp.SAMSUNG -> timers.flatMap {
            // One UI 5.1's controls of the running timer: each a clickable container around a clickable Button of the same label.
            listOf(
                Element("Delete", "android.view.ViewGroup", "105,1998,454,2203", delete = true),
                Element("Delete", "android.widget.Button", "147,2037,454,2163", delete = true),
                Element("Pause", "android.view.ViewGroup", "626,1998,975,2203"),
                Element("Pause", "android.widget.Button", "626,2037,933,2163"),
            )
        }
        screen == "timers" -> timers.map { Element("Delete", "android.widget.Button", "0,900,300,980", delete = true) }
        else -> emptyList()
    }

    private fun has(extras: Map<String, String>): String {
        val className = extras["class"]
        val found = elements().filter { element ->
            FakeScreen.has(listOf(element.label), extras) == 1 && (className == null || element.className.endsWith(".$className"))
        }
        return "count=${found.size} bounds=${found.firstOrNull()?.bounds ?: "none"} app=$front"
    }

    private fun tap(extras: Map<String, String>): String {
        val all = elements()
        val matches = FakeScreen.taps(all.map { it.label }, extras)
        val target = matches.firstOrNull()?.let { all[it] } ?: return "tapped=false count=0"
        when {
            tip && target.label == "OK" -> tip = false
            target.delete -> {
                timers.removeFirstOrNull()?.let { alarms.remove(it) }
                ongoing = false
                localOnly = false
            }
        }
        return "tapped=true count=${matches.size}"
    }

    companion object {
        const val ELAPSED = 1_000_000L
        const val CLEAR = "pm clear ${ClockApp.GOOGLE}"
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

        // Google Clock's wakeups that are no alarm clocks, at the test's time and at another, before and after the setup.
        val run = FakeRun()
        val phone = ClockPhone(run)
        phone.wakeupAt(4, 45)
        assertEquals(SetupResult.Ready, ClockFree.apply(run.scope))
        phone.wakeupAt(6, 45)
        assertFalse(NextAlarmAt.passes(run.scope, none))
        assertTrue(run.restores.runAll().isEmpty())
    }

    @Test
    fun clockFreeTakesTheTestTimeOnlyWhenGoogleClockHoldsNoAlarmAndNoTimer() {
        val run = FakeRun()
        ClockPhone(run)

        assertEquals(SetupResult.Ready, ClockFree.apply(run.scope))
        assertEquals("6:45", run.scope.values.time)
        assertEquals("6", run.scope.values.hour)
        assertEquals("45", run.scope.values.minute)
        assertEquals(ClockApp.GOOGLE, run.scope.notes[ClockApp.NOTE])
        assertEquals(1, run.restores.size)
        assertTrue(run.adb.commands.toString(), run.adb.commands.none { it.startsWith("pm ") || it.startsWith("am ") || it.startsWith("input ") })
        assertTrue(run.adb.hookCalls("SCREEN_HAS").isEmpty() && run.adb.hookCalls("SCREEN_TAP").isEmpty())

        // An alarm clock of the clock's at any time, a running timer, a paused timer's ongoing notification, or another notification of the clock's.
        val holds = listOf<(ClockPhone) -> Unit>({ it.alarmAt(7, 0) }, { it.timerIn(3_600) }, { it.ongoing = true }, { it.notified = true })
        for (hold in holds) {
            val held = FakeRun()
            val phone = ClockPhone(held)
            hold(phone)
            val before = phone.alarms.toList()

            val result = ClockFree.apply(held.scope)
            assertTrue("$result", result is SetupResult.Untestable)
            assertEquals(0, held.restores.size)
            assertTrue(held.adb.commands.none { it.startsWith("pm ") || it.startsWith("am ") })
            assertEquals(before, phone.alarms)
        }
        // An alarm clock of another app is no alarm of the clock's.
        val other = FakeRun()
        ClockPhone(other).alarmAt(7, 0, packageName = "com.example.other")
        assertEquals(SetupResult.Ready, ClockFree.apply(other.scope))
    }

    @Test
    fun theRestoreClearsGoogleClockOnceAndChecksThatNoAlarmClockIsLeft() {
        val run = FakeRun()
        val phone = ClockPhone(run)
        assertEquals(SetupResult.Ready, ClockFree.apply(run.scope))

        assertFalse(NextAlarmAt.passes(run.scope, none))
        phone.alarmAt(6, 45, packageName = "com.example.other")
        phone.alarmAt(6, 40)
        assertFalse(NextAlarmAt.passes(run.scope, none))
        // A live run's alarm at the test's time and another at 18:45 today.
        phone.setAlarm(6, 45)
        phone.alarmAt(18, 45, day = 23)
        assertTrue(NextAlarmAt.passes(run.scope, none))

        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(ClockPhone.CLEAR), run.adb.commands.filter { it.startsWith("pm ") })
        assertEquals(0, phone.clocks())
        assertFalse(NextAlarmAt.passes(run.scope, none))
        assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
        assertTrue(run.adb.commands.none { it.startsWith("am force-stop") })

        // A clear that leaves an alarm clock fails the restore, which names what to do by hand.
        val stuck = FakeRun()
        val stuckPhone = ClockPhone(stuck)
        ClockFree.apply(stuck.scope)
        stuckPhone.setAlarm(6, 45)
        stuckPhone.stuck = true
        val failure = stuck.restores.runAll().single()
        assertTrue(failure.reason, failure.reason.contains("still holds 1 alarm"))
        assertTrue(failure.byHand, failure.byHand.contains("Clock > Alarms"))
    }

    @Test
    fun theRestoreClearsNoAppOnAnyDeviceButKestrelApi37() {
        // On the A71 the restore refuses before any command.
        val a71 = FakeRun(DeviceKind.A71)
        ClockPhone(a71).setAlarm(6, 45)
        assertThrows(IllegalStateException::class.java) { ClockClear.run(a71.scope) }
        assertTrue(a71.adb.commands.isEmpty())

        // Another device that took kestrel-api37's serial after the setup: the device's own check refuses the clear.
        val run = FakeRun()
        val phone = ClockPhone(run)
        assertEquals(SetupResult.Ready, ClockFree.apply(run.scope))
        phone.setAlarm(6, 45)
        run.adb.props["ro.boot.qemu.avd_name"] = "zoran"
        assertEquals(1, run.restores.runAll().size)
        assertTrue(run.adb.commands.none { it.startsWith("pm ") })
        assertEquals(1, phone.clocks())

        // The same for the timer's restore.
        val timer = FakeRun()
        val timerPhone = ClockPhone(timer)
        assertEquals(SetupResult.Ready, TimerCleanup.apply(timer.scope))
        timerPhone.timerIn(290)
        timer.adb.props["ro.boot.qemu.avd_name"] = "zoran"
        assertEquals(1, timer.restores.runAll().size)
        assertTrue(timer.adb.commands.none { it.startsWith("pm ") })
        assertEquals(1, timerPhone.alarms.size)
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
    fun onTheA71ANewTimerDueInFourToFiveMinutesWithItsOngoingNotificationPassesAndIsDeletedWithTheClocksTaps() {
        val run = FakeRun(DeviceKind.A71)
        val phone = ClockPhone(run, ClockApp.SAMSUNG)

        assertEquals(SetupResult.Ready, TimerCleanup.apply(run.scope))
        assertFalse(TimerRunning.passes(run.scope, none))
        phone.timerIn(290)
        assertFalse(TimerRunning.passes(run.scope, none))
        // Only an ongoing notification counts on the A71, where the owner's other notifications of the clock may be.
        phone.localOnly = true
        assertFalse(TimerRunning.passes(run.scope, none))
        phone.ongoing = true
        assertTrue(TimerRunning.passes(run.scope, none))
        // One UI's tip about the full screen may cover the timers page; it is dismissed with its own OK.
        phone.tip = true

        assertTrue(run.restores.runAll().isEmpty())
        assertTrue(phone.alarms.isEmpty())
        assertFalse(phone.tip)
        assertEquals(listOf("OK", "Delete"), run.adb.hookCalls("SCREEN_TAP").map { it["label"] })
        assertTrue(run.adb.commands.contains("am start -W -a android.intent.action.SHOW_TIMERS -p ${ClockApp.SAMSUNG}"))
        assertTrue(run.adb.commands.none { it.startsWith("am force-stop") || it.startsWith("pm ") })
    }

    @Test
    fun onKestrelApi37TheTimerNeedsAnEmptyGoogleClockAndItsRestoreClearsIt() {
        // An alarm clock of Google Clock's, a running timer, a paused timer's ongoing notification, or another notification.
        val holds = listOf<(ClockPhone) -> Unit>({ it.alarmAt(7, 0) }, { it.timerIn(3_600) }, { it.ongoing = true }, { it.notified = true }, { it.localOnly = true })
        for (hold in holds) {
            val held = FakeRun()
            val phone = ClockPhone(held)
            hold(phone)
            val before = phone.alarms.toList()

            val result = TimerCleanup.apply(held.scope)
            assertTrue("$result", result is SetupResult.Untestable)
            assertEquals(0, held.restores.size)
            assertTrue(held.adb.commands.none { it.startsWith("pm ") || it.startsWith("am ") })
            assertEquals(before, phone.alarms)
        }
        val samsung = FakeRun()
        ClockPhone(samsung, ClockApp.SAMSUNG)
        assertTrue(TimerCleanup.apply(samsung.scope) is SetupResult.Untestable)
        assertEquals(0, samsung.restores.size)

        val run = FakeRun()
        val phone = ClockPhone(run)
        assertEquals(SetupResult.Ready, TimerCleanup.apply(run.scope))
        phone.timerIn(290)
        assertFalse(TimerRunning.passes(run.scope, none))
        // A cleared Google Clock posts its running timer's notification without FLAG_ONGOING_EVENT.
        phone.localOnly = true
        assertTrue(TimerRunning.passes(run.scope, none))

        assertTrue(run.restores.runAll().isEmpty())
        assertEquals(listOf(ClockPhone.CLEAR), run.adb.commands.filter { it.startsWith("pm ") })
        assertTrue(phone.alarms.isEmpty())
        assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
        assertTrue(run.adb.commands.none { it.contains("SHOW_TIMERS") || it.startsWith("am force-stop") })

        // A clear that leaves the timer fails the restore, which names what to do by hand.
        val stuck = FakeRun()
        val stuckPhone = ClockPhone(stuck)
        assertEquals(SetupResult.Ready, TimerCleanup.apply(stuck.scope))
        stuckPhone.timerIn(290)
        stuckPhone.stuck = true
        val failure = stuck.restores.runAll().single()
        assertTrue(failure.reason, failure.reason.contains("1 timer"))
        assertTrue(failure.byHand, failure.byHand.contains("Clock > Timer"))
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
