package dev.kestrel.eval.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Only kestrel-api37 and the A71, never the S23+ (design D3 of sub-project 6). */
class DeviceGuardTest {
    private val clock = FakeClock()
    private val guard = DeviceGuard(clock)

    @Test
    fun acceptsKestrelApi37ByItsAvdNameAndItsConsole() {
        assertEquals(TestDevice("emulator-5554", DeviceKind.EMULATOR, 37), guard.accept(FakeAdb()))
    }

    @Test
    fun refusesAnEmulatorWhoseConsoleNamesAnotherAvd() {
        val adb = FakeAdb().apply { consoleName = "zoran" }

        val refusal = assertThrows(Refusal::class.java) { guard.accept(adb) }

        assertEquals("Refusing emulator-5554 (model 'sdk_gphone64_x86_64', AVD 'kestrel-api37'). :tools:eval runs only on kestrel-api37 and the Galaxy A71.", refusal.message)
        // A busy emulator sometimes answers nothing, so a refusal is checked twice more, 2 s apart.
        assertEquals(listOf(2.seconds, 2.seconds), clock.sleeps)
    }

    @Test
    fun acceptsTheA71ByItsSerialAndItsModel() {
        val adb = FakeAdb(serial = "R58N66RVD7Z", model = "SM-A715F", avd = "", sdk = 33)

        assertEquals(TestDevice("R58N66RVD7Z", DeviceKind.A71, 33), guard.accept(adb))
    }

    @Test
    fun refusesAnA71ModelWithAnotherSerial() {
        val adb = FakeAdb(serial = "R58N00000000", model = "SM-A715F", avd = "", sdk = 33)

        assertThrows(Refusal::class.java) { guard.accept(adb) }
    }

    @Test
    fun refusesTheS23PlusAtOnceWithItsOwnMessage() {
        val adb = FakeAdb(serial = "R5CW00000000", model = "SM-S916B", avd = "", sdk = 36)

        val refusal = assertThrows(Refusal::class.java) { guard.accept(adb) }

        assertEquals("Refusing R5CW00000000: it is the owner's Galaxy S23+ (SM-S916B), which :tools:eval never uses.", refusal.message)
        assertTrue(clock.sleeps.isEmpty())
    }

    @Test
    fun refusesADeviceThatIsNotConnected() {
        val adb = FakeAdb().apply { state = "offline" }

        val refusal = assertThrows(Refusal::class.java) { guard.accept(adb) }

        assertEquals("Device emulator-5554 is not connected.", refusal.message)
    }
}
