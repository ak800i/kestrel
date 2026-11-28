package dev.kestrel.eval.run

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.Refusal
import dev.kestrel.eval.device.ShellResult
import dev.kestrel.eval.hook.HookError
import dev.kestrel.eval.hook.HookSilent
import dev.kestrel.eval.hook.ProviderState
import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

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

        // Plan 7G's mark of the hooks' own conversation, with which the series' cleanup leaves the owner's open.
        run.adb.hook("CONVERSATION_STATE") { FakeAdb.NO_CONVERSATION.removeSuffix(" typed=no") }
        assertEquals(
            "Kestrel on emulator-5554 answers no conversation-state's typed= and conversation-control do=expire-typed, as a build from before plan 7G of sub-project 7 does; install the current debug build with scripts\\install.ps1.",
            assertThrows(Refusal::class.java) { preflight().prepare(needsTestApp = false) }.message,
        )

        // Plan 7G's request=, with which a run's restore sees the hooks' own request that still runs.
        run.adb.hook("CONVERSATION_STATE") { FakeAdb.NO_CONVERSATION.replace(" request=none", "") }
        assertEquals(
            "Kestrel on emulator-5554 answers no conversation-state's request=, as a build from before plan 7G of sub-project 7 does; install the current debug build with scripts\\install.ps1.",
            assertThrows(Refusal::class.java) { preflight().prepare(needsTestApp = false) }.message,
        )

        run.adb.hook("CONVERSATION_STATE") { FakeAdb.NO_CONVERSATION }
        // Plan 7G's stop of the hooks' own work only, with which a run's restore leaves the owner's goal and request alone.
        run.adb.hookFails("GOAL_CONTROL", "add --es do with one of tap, touch, speak, focus, refuse, refuse-off or send-later")
        assertEquals(
            "Kestrel on emulator-5554 answers no goal-control do=stop-typed, as a build from before plan 7G of sub-project 7 does; install the current debug build with scripts\\install.ps1.",
            assertThrows(Refusal::class.java) { preflight().prepare(needsTestApp = false) }.message,
        )

        run.adb.hook("GOAL_CONTROL") { "stop=none" }
        run.adb.hookFails("CONVERSATION_CONTROL", "add --es do with one of new, expire, limits or restore-check")
        assertEquals(
            "Kestrel on emulator-5554 answers no conversation-control do=delete-typed, as a build from before plan 7F of sub-project 7 does; install the current debug build with scripts\\install.ps1.",
            assertThrows(Refusal::class.java) { preflight().prepare(needsTestApp = false) }.message,
        )
    }

    private fun preflight(of: FakeRun = run) = Preflight(of.device, of.hooks, of.restores, of.clock, installer, ConversationGuard(of.hooks, of.clock))

    @Test
    fun testModeIsTurnedOnWithARestore() {
        preflight().prepare(needsTestApp = true)

        assertEquals("on", testMode)
        assertTrue(installs.isEmpty())
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
        val off = run.clock.time
        assertEquals(
            "Kestrel's accessibility service is off on emulator-5554: it was not bound within 30 s. Turn it on first.",
            assertThrows(Refusal::class.java) { preflight().prepare(false) }.message,
        )
        // The wait reads until less than one 1 s poll of its 30 s is left, and never past them.
        assertTrue((run.clock.time - off).inWholeSeconds in 29..30)

        run.adb.hook("STATE") { "screen=on testMode=on" }
        run.adb.hook("VOICE_STATE") { "mode=off session=closed" }
        val start = run.clock.time
        assertThrows(Refusal::class.java) { preflight().prepare(false) }
        // The wait reads until less than one 3 s poll of its 90 s is left, and never past them.
        assertTrue((run.clock.time - start).inWholeSeconds in 87..90)
    }

    // Plan 7G's review of the hooks' transport, M1: the hook's content call starts Kestrel's process after a cold boot, and
    // Android binds the accessibility service only after that, so the first state can answer screen=off; the preparation
    // waits for screen=on instead of refusing at once.
    @Test
    fun theAccessibilityServiceThatBindsAfterKestrelsStartIsWaitedFor() {
        var states = 0
        run.adb.hook("STATE") { if (++states <= 2) "screen=off testMode=on" else "screen=on testMode=on" }

        preflight().prepare(needsTestApp = false)

        assertTrue(states > 2)
    }
    // The re-check of the hooks' transport, M1: only a lost answer is "not yet". A Kestrel without hooks and Kestrel's own
    // error answer are answers, so the wait stops at once with their own message instead of calling the service off.
    @Test
    fun theWaitForTheAccessibilityServiceStopsAtOnceWhenKestrelHasNoHooks() {
        run.adb.on("--method STATE ") { FakeAdb.NO_HOOKS }
        val start = run.clock.time

        assertThrows(HookSilent::class.java) { preflight().prepare(false) }

        assertEquals(start, run.clock.time)
    }

    @Test
    fun theWaitForTheAccessibilityServiceStopsAtOnceOnKestrelsErrorAndReadsAgainOnlyAfterALostAnswer() {
        run.adb.hookFails("STATE", "the command failed: IllegalStateException")
        val start = run.clock.time
        assertEquals("the command failed: IllegalStateException", assertThrows(HookError::class.java) { preflight().prepare(false) }.message)
        assertEquals(start, run.clock.time)

        run.adb.hook("STATE") { "screen=on testMode=on" }
        repeat(3) { run.adb.dieNext("--method STATE") }
        preflight().prepare(false)
        assertTrue(run.clock.time > start)
    }

    @Test
    fun theWaitForListeningStopsAtOnceOnKestrelsErrorAnswer() {
        run.adb.hookFails("VOICE_STATE", "the command failed: IllegalStateException")
        val start = run.clock.time
        assertThrows(HookError::class.java) { preflight().waitListening() }
        assertEquals(start, run.clock.time)
    }
    // The whole-plan review of plan 7G, I2: the wait for listening keeps the rule of every wait, so a slow adb ends it at its
    // 90 s, not after a full timeout of each read.
    @Test
    fun theWaitForListeningEndsAtItsDeadlineHoweverSlowAdbIs() {
        run.adb.hook("VOICE_STATE") { "mode=off session=closed" }
        run.adb.slow(run.clock, 2.minutes) { "VOICE_STATE" in it }
        val start = run.clock.time
        assertThrows(IllegalStateException::class.java) { preflight().waitListening() }
        assertEquals(90.seconds, run.clock.time - start)
    }

    // I2 too: three lost answers end one read (Device.read's tries), which the wait takes for "not yet": it reads again and
    // finds Kestrel listening.
    @Test
    fun theWaitForListeningReadsAgainAfterAnAnswerThatAdbLost() {
        repeat(3) { run.adb.loseNext("VOICE_STATE") }
        preflight().waitListening()
        assertEquals(4, run.adb.commands.count { "VOICE_STATE" in it })
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

    // Plan 7G: a build without voice-restore is from before this plan, and a restart of Kestrel could not wait out its guard
    // against a crash loop.
    @Test
    fun aBuildWithoutVoiceRestoreIsARefusal() {
        run.adb.hookFails("VOICE_RESTORE", "unknown command 'VOICE_RESTORE'")

        assertEquals(
            "Kestrel on emulator-5554 answers no voice-restore, as a build from before plan 7G of sub-project 7 does; install the current debug build with scripts\\install.ps1.",
            assertThrows(Refusal::class.java) { preflight().prepare(needsTestApp = false) }.message,
        )
    }
}
