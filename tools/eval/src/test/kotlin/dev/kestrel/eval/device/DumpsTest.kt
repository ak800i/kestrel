package dev.kestrel.eval.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The readers of Android's dumps, with the dumps of the Pester tests that tested them in PowerShell (design D5 of sub-project 6). */
class DumpsTest {
    @Test
    fun wazeNavigatesWhenItsNotificationIsOngoing() {
        val dump = listOf(
            "  NotificationRecord(0x0a pkg=com.example.chat user=UserHandle{0} id=1 tag=null importance=3)",
            "    flags=0x2",
            "  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7 tag=null importance=2)",
            "    flags=0x62",
        )

        assertTrue(Dumps.wazeNavigating(dump))
        assertFalse(Dumps.wazeNavigating(listOf("  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7)", "    flags=0x10")))
    }

    @Test
    fun theHotspotAndAValidatedWifi() {
        assertTrue(Dumps.hotspotOn(listOf("Tethering:", "  wlan1 - TetheredState")))
        assertFalse(Dumps.hotspotOn(listOf("Tethering:", "  wlan1 - AvailableState")))
        assertTrue(Dumps.wifiValidated(listOf("  Capabilities: INTERNET&NOT_RESTRICTED&VALIDATED Transports: WIFI")))
        assertFalse(Dumps.wifiValidated(listOf("  Capabilities: INTERNET&NOT_RESTRICTED Transports: WIFI")))
    }

    @Test
    fun thePressesAndTheImagesOfTheTree() {
        assertEquals(listOf("send", "share"), Dumps.pressKinds(listOf("send 1795086000000", "", "share 1795086001000", "nonsense")))
        val json = """{"windows":[{"id":3,"kind":"APPLICATION","active":true},{"id":4,"kind":"SYSTEM","active":false}],"elements":[""" +
            """{"windowId":3,"className":"android.widget.ImageView"},{"windowId":3,"className":"android.widget.ImageView"},""" +
            """{"windowId":3,"className":"android.widget.TextView"},{"windowId":4,"className":"android.widget.ImageView"}]}"""
        assertEquals(2, Dumps.treeImageCount(json))
        assertEquals(0, Dumps.treeImageCount("""{"windows":[],"elements":[]}"""))
    }

    @Test
    fun thePackageInFront() {
        val activities = listOf(
            "Display #0 (activities from top to bottom):",
            "  * Task{6c1 #12 type=standard A=1000:com.android.settings}",
            "  topResumedActivity=ActivityRecord{9f3e2b1 u0 com.android.settings/.Settings t12}",
        )

        assertEquals("com.android.settings", Dumps.resumedPackage(activities))
        assertEquals(
            "com.sec.android.app.launcher",
            Dumps.resumedPackage(listOf("  mResumedActivity: ActivityRecord{1 u0 com.sec.android.app.launcher/.activities.LauncherActivity t1}")),
        )
        assertNull(Dumps.resumedPackage(listOf("  topResumedActivity=null")))
    }

    @Test
    fun thePermissionsRowsCallsAccountsPackagesAndAppOps() {
        val dump = listOf(
            "      runtime permissions:",
            "        android.permission.RECORD_AUDIO: granted=true, flags=[ USER_SET ]",
            "        android.permission.READ_CONTACTS: granted=false, flags=[ ]",
        )
        assertEquals(
            listOf("android.permission.RECORD_AUDIO"),
            Dumps.grantedPermissions(dump, listOf("android.permission.RECORD_AUDIO", "android.permission.READ_CONTACTS")),
        )
        assertEquals(12L, Dumps.largestRowId(listOf("Row: 0 _id=3", "Row: 1 _id=12", "Row: 2 _id=7")))
        assertEquals(0L, Dumps.largestRowId(listOf("No result found.")))
        assertEquals(0, Dumps.rowCount(listOf("No result found.")))
        assertTrue(Dumps.callActive(listOf("  mCallState=0", "  mCallState=2")))
        assertFalse(Dumps.callActive(listOf("  mCallState=0")))
        val accounts = listOf("Accounts: 2", "    Account {name=pera@example.com, type=com.google}", "    Account {name=Pera, type=org.telegram.messenger}")
        assertEquals(1, Dumps.accountCount(accounts, "com.google"))
        assertTrue(Dumps.packageListed(listOf("package:dev.kestrel.testapp", "package:dev.kestrel"), "dev.kestrel"))
        assertFalse(Dumps.packageListed(listOf("package:dev.kestrel.testapp"), "dev.kestrel"))
        assertEquals("allow", Dumps.appOpMode(listOf("SYSTEM_ALERT_WINDOW: allow; time=+1m2s ago"), "SYSTEM_ALERT_WINDOW"))
        assertEquals("default", Dumps.appOpMode(listOf("No operations.", "Default mode: allow"), "SYSTEM_ALERT_WINDOW"))
        assertEquals("default", Dumps.appOpMode(listOf("Uid mode: SYSTEM_ALERT_WINDOW: ignore"), "SYSTEM_ALERT_WINDOW"))
    }
}
