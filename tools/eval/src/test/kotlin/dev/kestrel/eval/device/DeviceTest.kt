package dev.kestrel.eval.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Paths
import kotlin.time.Duration.Companion.seconds

/** The guarded device and its retry rule: reads are tried 3 times, a command that acts runs once (design D3 of sub-project 6). */
class DeviceTest {
    private val clock = FakeClock()
    private val adb = FakeAdb()
    private val device = Device(adb, TestDevice("emulator-5554", DeviceKind.EMULATOR, 37), clock)

    @Test
    fun everyCommandRunsInsideTheGuardsCheckInTheSameShellCall() {
        adb.on("^dumpsys battery$") { "  level: 100" }

        assertEquals(listOf("  level: 100"), device.read("dumpsys battery"))
        assertEquals(
            "case \$(getprop ro.boot.qemu.avd_name) in kestrel-api37) dumpsys battery && echo && echo kestrel-end;; *) echo kestrel-refused;; esac",
            adb.raw.single(),
        )
        val a71 = Device(FakeAdb(serial = "R58N66RVD7Z", model = "SM-A715F", avd = ""), TestDevice("R58N66RVD7Z", DeviceKind.A71, 33), clock)
        assertEquals(
            "case \$(getprop ro.product.model) in SM-A715F*) true && echo && echo kestrel-end;; *) echo kestrel-refused;; esac",
            a71.guarded("true"),
        )
    }

    @Test
    fun anEmptyAnswerIsNoFailure() {
        assertEquals(emptyList<String>(), device.idempotent("settings put global zen_mode 0"))
    }

    @Test
    fun aReadIsTriedAgainAfterALostAnswer() {
        adb.on("^getprop ro.build.version.sdk$") { "37" }
        adb.loseNext("^getprop")

        assertEquals(listOf("37"), device.read("getprop ro.build.version.sdk"))
        assertEquals(2, adb.commands.size)
        assertEquals(listOf(2.seconds), clock.sleeps)
    }

    @Test
    fun aReadFailsAfterThreeLostAnswers() {
        repeat(3) { adb.loseNext("^dumpsys") }

        val failure = assertThrows(DeviceCommandFailed::class.java) { device.read("dumpsys tethering") }

        assertEquals("Could not run 'dumpsys tethering' on emulator-5554: error: closed", failure.message)
        assertEquals(3, adb.commands.size)
    }

    @Test
    fun aCommandThatActsIsNeverSentTwice() {
        adb.loseNext("^input keyevent")

        assertNull(device.once("input keyevent KEYCODE_ENDCALL"))
        assertEquals(listOf("input keyevent KEYCODE_ENDCALL"), adb.commands)
        assertEquals(emptyList<kotlin.time.Duration>(), clock.sleeps)
        assertEquals(emptyList<String>(), device.once("input keyevent KEYCODE_ENDCALL"))
    }

    @Test
    fun anotherDeviceThatTookTheSerialStopsEveryCommand() {
        adb.props["ro.boot.qemu.avd_name"] = "zoran"

        val changed = assertThrows(DeviceChanged::class.java) { device.read("dumpsys battery") }

        assertEquals("Refusing to run 'dumpsys battery' on emulator-5554: it is no longer the test device that :tools:eval accepted.", changed.message)
        assertEquals(emptyList<String>(), adb.commands)
    }

    @Test
    fun theConsoleIsCheckedRightBeforeEachEmuCommand() {
        device.emu(listOf("sms", "send", "+12025550177", "maple"))
        adb.consoleName = "zoran"

        assertThrows(DeviceChanged::class.java) { device.emu(listOf("sms", "send", "+12025550177", "heron")) }
        assertEquals(
            listOf(listOf("emu", "avd", "name"), listOf("emu", "sms", "send", "+12025550177", "maple"), listOf("emu", "avd", "name")),
            adb.hostCommands,
        )
    }

    @Test
    fun aPushChecksTheDeviceFirst() {
        val file = Paths.get("build", "task.json").toAbsolutePath()

        device.push(file, "/data/local/tmp/kestrel-task.json")

        assertEquals(listOf("true"), adb.commands)
        assertEquals(listOf(listOf("push", file.toString(), "/data/local/tmp/kestrel-task.json")), adb.hostCommands)
    }
}
