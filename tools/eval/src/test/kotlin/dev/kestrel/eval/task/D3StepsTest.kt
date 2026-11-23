package dev.kestrel.eval.task

import dev.kestrel.eval.config.LocalConfig
import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The steps of the D3 tasks: the hotspot, the test e-mail, the test location, Waze's Home and the test name (design D5 and D8 of sub-project 6). */
class D3StepsTest {
    private val config = LocalConfig.of(mapOf("gmailAddress" to "pera.test@example.com", "emulatorGmailAddress" to "pera.second@example.com", "testLocation" to "44.8100,20.4600"))
    private val compose = "  topResumedActivity=ActivityRecord{9f3e2b1 u0 com.google.android.gm/.ComposeActivityGmail t12}"

    @Test
    fun aHotspotThatIsOffNeedsNoStopAndAValidatedWifiNoEnable() {
        val run = FakeRun()
        run.adb.on("^dumpsys tethering$") { "  wlan1 - AvailableState" }
        run.adb.on("^dumpsys connectivity$") { "  Capabilities: INTERNET&NOT_RESTRICTED&VALIDATED Transports: WIFI" }
        run.adb.hook("SCREEN_HAS") { "count=1 app=com.android.settings" }

        assertEquals(SetupResult.Ready, HotspotOff.apply(run.scope))
        run.restores.runAll()

        assertFalse(run.adb.commands.contains("cmd wifi stop-softap"))
        assertFalse(run.adb.commands.contains("svc wifi enable"))
        assertEquals(mapOf("package" to "com.android.settings", "contains" to "Wi-Fi hotspot"), run.adb.hookCalls("SCREEN_HAS").single())
    }

    @Test
    fun aHotspotThatTheDeviceDoesNotLetTheToolTurnOffMakesTheTaskUntestableWithoutARestore() {
        val run = FakeRun()
        run.adb.on("^dumpsys tethering$") { "  wlan1 - TetheredState" }
        run.adb.on("^cmd wifi stop-softap$") { null }
        run.adb.hook("SCREEN_HAS") { "count=0 app=com.android.settings" }

        assertTrue(HotspotOff.apply(run.scope) is SetupResult.Untestable)
        assertTrue(run.adb.commands.contains("cmd wifi stop-softap"))
        assertEquals(0, run.restores.size)
    }

    @Test
    fun theHotspotsSwitchTurnsItOffWhenTheShellMayNot() {
        val run = FakeRun()
        var on = true
        run.adb.on("^dumpsys tethering$") { if (on) "  wlan1 - TetheredState" else "  wlan1 - AvailableState" }
        run.adb.on("^dumpsys connectivity$") { "  Capabilities: INTERNET&VALIDATED Transports: WIFI" }
        run.adb.on("^cmd wifi stop-softap$") { null }
        run.adb.on("^input tap ") { on = false; "" }
        run.adb.hook("SCREEN_HAS") { extras -> if (extras["state"] == "checked" && extras["label"] == "Wi-Fi hotspot") "count=1 bounds=100,200,300,400 app=com.android.settings" else "count=1 app=com.android.settings" }

        assertEquals(SetupResult.Ready, HotspotOff.apply(run.scope))

        assertTrue(run.adb.commands.contains("input tap 200 300"))
        assertTrue(HotspotOn.passes(run.scope, HookAnswer.EMPTY).not())
    }

    @Test
    fun theTestEmailIsSentToTheDevicesOwnAddressAndTheInboxOpensInANewTask() {
        val run = FakeRun(config = config)
        run.adb.on("^dumpsys activity activities$") { compose }
        run.adb.hook("SCREEN_TAP") { "tapped=true count=1" }
        var polls = 0
        run.adb.hook("SCREEN_HAS") { if (++polls < 2) "count=0 app=com.google.android.gm" else "count=1 app=com.google.android.gm" }

        assertEquals(SetupResult.Ready, MailToSelf.apply(run.scope))

        assertEquals(
            listOf(
                "am start -W -a android.intent.action.SENDTO -d 'mailto:pera.second@example.com?subject=Kestrel%20test%20maple%20heron&body=maple%20heron' -p com.google.android.gm",
                "am start -W -f 0x10008000 -n com.google.android.gm/.ConversationListActivityGmail",
            ),
            run.adb.commands.filter { it.startsWith("am start") },
        )
        assertEquals(mapOf("label" to "Send"), run.adb.hookCalls("SCREEN_TAP").single())
        assertEquals(SetupResult.Untestable("corpus\\owner\\a71-config.json gives no address of this device's Gmail account"), MailToSelf.apply(FakeRun().scope))
    }

    @Test
    fun theTestEmailsSendIsNeverTappedWhenGmailsComposeScreenDidNotOpen() {
        val run = FakeRun(config = config)
        run.adb.on("^am start -W -a android.intent.action.SENDTO ") { null }
        run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{9f3e2b1 u0 org.telegram.messenger.web/org.telegram.ui.LaunchActivity t12}" }
        run.adb.hook("SCREEN_TAP") { "tapped=true count=1" }

        val failure = assertThrows(SetupFailed::class.java) { MailToSelf.apply(run.scope) }

        assertEquals("Gmail's compose screen did not open.", failure.message)
        assertTrue(run.adb.hookCalls("SCREEN_TAP").isEmpty())
    }

    @Test
    fun theMailSetupFailsAtOnceWhenAnotherAppCoversGmailThreeTimes() {
        val run = FakeRun(config = config)
        run.adb.on("^dumpsys activity activities$") { compose }
        run.adb.hook("SCREEN_TAP") { "tapped=true count=1" }
        run.adb.hook("SCREEN_HAS") { "count=0 app=com.google.android.permissioncontroller" }

        val failure = assertThrows(SetupFailed::class.java) { MailToSelf.apply(run.scope) }

        assertEquals("Gmail's inbox did not show: com.google.android.permissioncontroller is in front of it.", failure.message)
        assertEquals(3, run.adb.hookCalls("SCREEN_HAS").size)
    }

    @Test
    fun theTestLocationSendsFixesAndPutsBackTheMockModeAndTheSwitch() {
        val run = FakeRun(DeviceKind.A71, config = config)
        run.adb.on("^appops get com.android.shell android:mock_location$") { "MOCK_LOCATION: ignore; time=+5m ago" }
        run.adb.on("^cmd location is-location-enabled$") { "false" }
        val fixes = mutableListOf<String>()
        val location = TestLocation { _: Device, at: String -> fixes.add("start $at"); AutoCloseable { fixes.add("stop") } }

        assertEquals(SetupResult.Ready, location.apply(run.scope))
        run.restores.runAll()

        assertEquals(listOf("start 44.8100,20.4600", "stop"), fixes)
        assertEquals(
            "appops set com.android.shell android:mock_location allow; for p in gps network fused; do cmd location providers remove-test-provider \$p; done; " +
                "appops set com.android.shell android:mock_location ignore; cmd location set-location-enabled false",
            run.adb.commands.last(),
        )
        assertTrue(TestLocation().apply(FakeRun(DeviceKind.A71).scope) is SetupResult.Untestable)
    }

    @Test
    fun wazeHomeIsTestableOnlyWhenItsLinkStartsANavigation() {
        val run = FakeRun(DeviceKind.A71)
        run.adb.on("^pm list packages com.waze$") { "package:com.waze" }
        var started = false
        run.adb.on("^am start -a android.intent.action.VIEW -d 'waze://") { started = true; "" }
        run.adb.on("^am force-stop com.waze$") { started = false; "" }
        run.adb.on("^dumpsys notification$") { if (started) "  NotificationRecord(0x0b pkg=com.waze user=UserHandle{0} id=7)\n    flags=0x62" else "" }

        assertEquals(SetupResult.Ready, WazeHome.apply(run.scope))
        assertFalse(started)

        run.adb.on("^dumpsys notification$") { "" }
        assertEquals(SetupResult.Untestable("Waze shows no navigation to Home at the test location"), WazeHome.apply(run.scope))
        run.adb.on("^pm list packages com.waze$") { "" }
        assertEquals(SetupResult.Untestable("Waze is not installed"), WazeHome.apply(run.scope))
    }

    @Test
    fun theTestNameIsTheCommandsOrOneThatTheOwnersTelegramHasNoChatWith() {
        val run = FakeRun(DeviceKind.A71)
        run.adb.on("^pm list packages org.telegram.messenger.web$") { "package:org.telegram.messenger.web" }
        run.adb.hook("SCREEN_HAS") { extras -> if (extras["contains"] == "Ana") "count=2 app=org.telegram.messenger.web" else "count=0 app=org.telegram.messenger.web" }

        TestName("Ana", telegram = true).apply(run.scope)
        assertEquals("Mira", run.scope.values.name)

        val given = FakeRun(DeviceKind.A71).apply { scope.values.name = "Zora" }
        TestName("Ana", telegram = true).apply(given.scope)
        assertEquals("Zora", given.scope.values.name)
        assertTrue(given.adb.commands.isEmpty())

        val fixture = FakeRun()
        TestName("John", telegram = false).apply(fixture.scope)
        assertEquals("John", fixture.scope.values.name)
    }
}
