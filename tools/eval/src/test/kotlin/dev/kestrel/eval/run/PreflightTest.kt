package dev.kestrel.eval.run

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.device.ShellResult
import dev.kestrel.eval.hook.ProviderState
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths

/** What every run needs before it, the live gate and the guard of the emulator's account and profile (design D1, D5 and D7 of sub-project 6). */
class PreflightTest {
    private val run = FakeRun()
    private val installs = mutableListOf<List<String>>()
    private val installer = Installer(Paths.get("repo")) { command, _ -> installs.add(command); ShellResult(0, "", "") }
    private var testMode = "off"

    init {
        run.adb.on("^pm list packages dev.kestrel$") { "package:dev.kestrel" }
        run.adb.on("^pm list packages dev.kestrel.testapp$") { "package:dev.kestrel.testapp" }
        run.adb.hook("STATE") { "screen=on testMode=$testMode numbers=0" }
        run.adb.hook("TEST_MODE") { extras -> testMode = if (extras["on"] == "true") "on" else "off"; "testMode=$testMode" }
        run.adb.hook("VOICE_STATE") { "mode=listening work=idle session=open clips=off" }
    }

    // Every run opens and closes a conversation of Kestrel's (design 14.1 of sub-project 7), which a build from before plan
    // 7D does not hold.
    @Test
    fun aBuildWithoutTheConversationsHooksIsARefusal() {
        run.adb.hookFails("CONVERSATION_STATE", "unknown command 'CONVERSATION_STATE'")

        val refusal = assertThrows(Refusal::class.java) { preflight().prepare(needsTestApp = false) }

        assertEquals(
            "Kestrel on emulator-5554 answers no conversation-state, as a build from before plan 7D of sub-project 7 does; install the current debug build with scripts\\install.ps1.",
            refusal.message,
        )

        run.adb.hook("CONVERSATION_STATE") { FakeAdb.NO_CONVERSATION }
        run.adb.hookFails("CONVERSATION_CONTROL", "add --es do with one of new, expire, limits or restore-check")
        assertEquals(
            "Kestrel on emulator-5554 answers no conversation-control do=delete-typed, as a build from before plan 7F of sub-project 7 does; install the current debug build with scripts\\install.ps1.",
            assertThrows(Refusal::class.java) { preflight().prepare(needsTestApp = false) }.message,
        )
    }

    private fun preflight(of: FakeRun = run) = Preflight(of.device, of.hooks, of.restores, of.clock, installer, ConversationGuard(of.hooks, of.clock))

    @Test
    fun testModeIsTurnedOnWithARestoreAndKestrelIsStartedFirst() {
        preflight().prepare(needsTestApp = true)

        assertEquals("on", testMode)
        assertTrue(installs.isEmpty())
        assertTrue(run.adb.commands.contains("am start -n dev.kestrel/.app.MainActivity"))
        run.restores.runAll()
        assertEquals("off", testMode)
    }

    @Test
    fun aMissingBuildIsInstalledThroughInstallPs1AndTheGradleDaemonStopped() {
        run.adb.on("^pm list packages dev.kestrel$") { "package:dev.kestrel.testapp" }
        run.adb.on("^pm list packages dev.kestrel.testapp$") { "" }

        preflight().prepare(needsTestApp = true)

        val script = Paths.get("repo").resolve("scripts")
        assertEquals(
            listOf(
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.resolve("install.ps1").toString(), "-Serial", "emulator-5554"),
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.resolve("gradle.ps1").toString(), "--stop"),
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.resolve("install.ps1").toString(), "-Serial", "emulator-5554", "-TestApp"),
                listOf("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.resolve("gradle.ps1").toString(), "--stop"),
            ),
            installs,
        )
        // On kestrel-api37 :testapp stays, as it always has; the restores are test mode's and the series' cleanup of the
        // hooks' own conversations.
        assertEquals(2, run.restores.size)
    }

    @Test
    fun theTestAppThatTheToolInstalledLeavesTheA71Again() {
        val a71 = FakeRun(DeviceKind.A71)
        var testApp = false
        a71.adb.on("^pm list packages dev.kestrel$") { "package:dev.kestrel" }
        a71.adb.on("^pm list packages dev.kestrel.testapp$") { if (testApp) "package:dev.kestrel.testapp" else "" }
        a71.adb.hook("STATE") { "screen=on testMode=on" }
        a71.adb.hook("VOICE_STATE") { "mode=listening session=open" }
        val installer = Installer(Paths.get("repo")) { command, _ -> if ("-TestApp" in command) testApp = true; ShellResult(0, "", "") }

        var uninstalls = true
        a71.adb.on("^pm uninstall dev\\.kestrel\\.testapp$") {
            if (uninstalls) testApp = false
            "Success"
        }

        Preflight(a71.device, a71.hooks, a71.restores, a71.clock, installer, ConversationGuard(a71.hooks, a71.clock)).prepare(needsTestApp = true)
        assertTrue(a71.restores.runAll().isEmpty())

        assertTrue(a71.adb.commands.contains("pm uninstall dev.kestrel.testapp"))
        uninstalls = false
        Preflight(a71.device, a71.hooks, a71.restores, a71.clock, installer, ConversationGuard(a71.hooks, a71.clock)).prepare(needsTestApp = true)
        assertEquals(listOf(":testapp removed from the A71"), a71.restores.runAll().map { it.what })
    }

    @Test
    fun anAccessibilityServiceThatIsOffOrKestrelNotListeningIsARefusal() {
        run.adb.hook("STATE") { "screen=off testMode=on" }
        assertEquals("Kestrel's accessibility service is off on emulator-5554. Turn it on first.", assertThrows(Refusal::class.java) { preflight().prepare(false) }.message)

        run.adb.hook("STATE") { "screen=on testMode=on" }
        run.adb.hook("VOICE_STATE") { "mode=off session=closed" }
        assertThrows(Refusal::class.java) { preflight().prepare(false) }
        assertTrue(run.clock.time.inWholeSeconds >= 90)
    }

    @Test
    fun theLiveGateNeedsACopilotProfileWithoutTheStandInAndPutsTheSessionBack() {
        var live = "off"
        run.adb.hook("AGENT_LIVE") { extras -> extras["on"]?.let { live = if (it == "true") "on" else "off" }; "live=$live" }
        run.adb.hook("PROVIDER_STANDIN") { "standin=off profile=other copilot=0 id=profile-2 credential=present" }
        assertThrows(Refusal::class.java) { LiveGate(run.hooks, run.restores, "emulator-5554").open() }

        run.adb.hook("PROVIDER_STANDIN") { "standin=on profile=copilot copilot=1 id=profile-1 credential=present" }
        assertThrows(Refusal::class.java) { LiveGate(run.hooks, run.restores, "emulator-5554").open() }

        run.adb.hook("PROVIDER_STANDIN") { "standin=off profile=copilot copilot=1 id=profile-1 credential=present" }
        LiveGate(run.hooks, run.restores, "emulator-5554").open()
        assertEquals("on", live)
        run.restores.runAll()
        assertEquals("off", live)

        // A sitting of a71-agent.ps1 opened it, so it stays open for the sitting to close.
        live = "on"
        LiveGate(run.hooks, run.restores, "emulator-5554").open()
        run.restores.runAll()
        assertEquals("on", live)
    }

    @Test
    fun theGuardStopsWhenTheEmulatorsGoogleAccountsOrProfileChanged() {
        var accounts = "    Account {name=pera@example.com, type=com.google}"
        run.adb.on("^dumpsys account$") { accounts }
        run.adb.hook("PROVIDER_STANDIN") { "standin=off profile=copilot copilot=1 id=profile-1 credential=present" }
        val guard = AccountGuard(run.device, run.hooks)

        val before = guard.read()
        guard.check(before)
        accounts = ""

        assertEquals(AccountGuard.Snapshot(1, ProviderState(false, "copilot", 1, "profile-1", "present")), before)
        val changed = assertThrows(AccountsChanged::class.java) { guard.check(before) }
        assertEquals("kestrel-api37's owner data changed during the command: Google accounts 1 before, 0 now. Check the emulator by hand.", changed.message)
    }
}
