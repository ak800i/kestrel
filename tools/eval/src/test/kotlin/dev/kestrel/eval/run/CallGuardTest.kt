package dev.kestrel.eval.run

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.FakeClock
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.device.TestDevice
import dev.kestrel.eval.hook.HookClient
import dev.kestrel.eval.hook.Hooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call before or after a run is ended with the end-call key, and a live session left on is turned off (design D2 and D5 of sub-project 6). */
class CallGuardTest {
    private val adb = FakeAdb(serial = "R58N66RVD7Z", model = "SM-A715F", avd = "", sdk = 33)
    private val clock = FakeClock()
    private val console = FakeConsole()
    private val device = Device(adb, TestDevice("R58N66RVD7Z", DeviceKind.A71, 33), clock)
    private val guard = CallGuard(device, console, clock)

    @Test
    fun noCallSendsNoKey() {
        adb.on("^dumpsys telephony.registry$") { "  mCallState=0" }

        assertFalse(guard.check())
        assertEquals(listOf("dumpsys telephony.registry"), adb.commands)
    }

    @Test
    fun aCallIsEndedWithTheEndCallKeyAndFailsTheRun() {
        var calling = true
        adb.on("^dumpsys telephony.registry$") { if (calling) "  mCallState=0\n  mCallState=2" else "  mCallState=0" }
        adb.on("^input keyevent KEYCODE_ENDCALL$") { calling = false; "" }

        assertTrue(guard.check())
        assertEquals(listOf("dumpsys telephony.registry", "input keyevent KEYCODE_ENDCALL", "dumpsys telephony.registry"), adb.commands)
        assertTrue(console.warnings.isEmpty())
    }

    @Test
    fun aLostKeyIsReadBackAndAStateThatCannotBeReadCountsAsNoCall() {
        var calling = true
        adb.on("^dumpsys telephony.registry$") { if (calling) "  mCallState=1" else "  mCallState=0" }
        adb.on("^input keyevent KEYCODE_ENDCALL$") { calling = false; "" }
        adb.loseNext("^input keyevent")

        assertTrue(guard.check())
        assertEquals(1, adb.commands.count { it.startsWith("input keyevent") })

        repeat(3) { adb.loseNext("^dumpsys telephony") }
        assertFalse(guard.check())
        assertEquals(listOf("The call state of R58N66RVD7Z could not be read."), console.warnings)
    }

    @Test
    fun aLiveSessionLeftOnIsTurnedOffWithAWarning() {
        var live = "on"
        adb.hook("AGENT_LIVE") { extras -> extras["on"]?.let { live = if (it == "true") "on" else "off" }; "live=$live" }
        val session = LiveSession(Hooks(HookClient(device)), console, "R58N66RVD7Z")

        session.ensureOff()
        session.ensureOff()

        assertEquals("off", live)
        assertEquals(listOf(emptyMap(), mapOf("on" to "false"), emptyMap()), adb.hookCalls("AGENT_LIVE"))
        assertEquals(1, console.warnings.size)
    }

    @Test
    fun aLiveSessionThatStaysOnIsARefusal() {
        adb.hook("AGENT_LIVE") { "live=on" }

        assertThrows(Refusal::class.java) { LiveSession(Hooks(HookClient(device)), console, "R58N66RVD7Z").ensureOff() }
    }
}
