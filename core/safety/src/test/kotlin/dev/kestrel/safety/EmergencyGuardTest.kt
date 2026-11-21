package dev.kestrel.safety

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the emergency guard, which `:app`'s instant commands and the agent share (design D13 of sub-project 5). */
class EmergencyGuardTest {
    @Test
    fun r1NamesEmergencyAndSosButNeverKestrelsOwnWindows() {
        val screen = TestScreen("com.android.systemui")
        screen.add(1800, "Emergency call")
        screen.add(1700, null, id = "sosButton", description = null)
        screen.add(1600, "Emergency", packageName = "dev.kestrel")
        screen.add(1500, "Emergent design")
        val snapshot = screen.snapshot()

        assertTrue(EmergencyGuard.emergencyNamed(snapshot, 0))
        assertTrue(EmergencyGuard.emergencyNamed(snapshot, 1))
        assertFalse(EmergencyGuard.emergencyNamed(snapshot, 2))
        assertFalse(EmergencyGuard.emergencyNamed(snapshot, 3))
    }

    @Test
    fun aNumberFieldAndItsDeleteKey() {
        val screen = TestScreen("com.google.android.dialer")
        screen.add(200, null, id = "digits", editable = false, clickable = false, text = "112")
        screen.add(200, null, editable = true, hint = "Search contacts", text = "Ana")
        screen.add(1800, "Delete", clickable = true)
        val snapshot = screen.snapshot()

        assertTrue(EmergencyGuard.isNumberField(snapshot.elements[0]))
        assertTrue(EmergencyGuard.showsNumber(snapshot.elements[0], "112"))
        assertFalse(EmergencyGuard.showsNumber(snapshot.elements[1], "Ana"))
        assertTrue(EmergencyGuard.isDeleteKey(snapshot.elements[2]))
        assertTrue(EmergencyGuard.showsEmergencyNumber(snapshot.elements[0]) { it == "112" })
    }

    @Test
    fun onlyALongNumberThatAndroidCallsNoEmergencyMayBeDialled() {
        val emergency = EmergencyNumbers { digits -> if (digits == "0601234567") false else if (digits == "999") null else true }

        assertTrue(EmergencyGuard.telAllowed("060 123 4567", emergency))
        assertFalse(EmergencyGuard.telAllowed("112", emergency))
        assertFalse(EmergencyGuard.telAllowed("999", emergency))
        assertFalse(EmergencyGuard.telAllowed("*#06#", emergency))
        assertFalse(EmergencyGuard.telAllowed("Ana", emergency))
        assertTrue(EmergencyGuard.mayCall("0601234567", false))
        assertFalse(EmergencyGuard.mayCall("0601234567", null))
    }
}
