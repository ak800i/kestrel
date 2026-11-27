package dev.kestrel.eval.task

import dev.kestrel.eval.config.Redactions
import dev.kestrel.eval.device.Deadline
import dev.kestrel.eval.device.DeviceCommandFailed
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookAnswer
import dev.kestrel.eval.run.EvalAccess
import dev.kestrel.eval.run.Restores
import dev.kestrel.eval.run.Stopping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Each step pushes its restore before its change, and each check reads only counts and flags (design D4 of sub-project 6). */
class StepsTest {
    private val run = FakeRun()

    // Plan 7G: a check that a wait reads goes through the run's scope bounded by the wait, so that none of its reads outlives
    // the wait's deadline, however slow adb is; the scope's notes, put-back record and tag are the run's.
    @Test
    fun aScopeBoundedByAWaitsDeadlineEndsItsReadsAtTheDeadlineAndSharesTheRunsState() {
        run.scope.tag = "t1"
        run.scope.notes["clock"] = "google"
        val bounded = run.scope.until(Deadline(run.clock, 10.seconds))
        run.adb.slow(run.clock, 2.minutes)

        assertThrows(DeviceCommandFailed::class.java) { bounded.hooks.state() }

        assertEquals(10.seconds, run.clock.time)
        assertEquals("t1", bounded.tag)
        assertEquals("google", bounded.notes["clock"])
        assertTrue(bounded.putBack === run.scope.putBack)
    }

    @Test
    fun forceStopStopsTheAppBeforeAndAfterTheRun() {
        ForceStop("org.mozilla.firefox").apply(run.scope)
        assertEquals(1, run.restores.size)

        run.restores.runAll()

        assertEquals(listOf("am force-stop org.mozilla.firefox", "am force-stop org.mozilla.firefox"), run.adb.commands)
    }

    @Test
    fun grantGivesOnlyTheMissingPermissionsAndTakesBackOnlyThose() {
        run.adb.granted[TEST_APP] = mutableSetOf(NOTIFICATIONS)

        Grant(TEST_APP, listOf(NOTIFICATIONS, CAMERA)).apply(run.scope)
        run.restores.runAll()

        assertEquals(listOf("dumpsys package $TEST_APP", "pm grant $TEST_APP $CAMERA", "pm revoke $TEST_APP $CAMERA"), run.adb.commands)
    }

    @Test
    fun afterCtrlCNoPermissionIsGrantedOnceTheRestoreThatTakesItBackRan() {
        var hook: Thread? = null
        run.adb.on("^pm grant ") {
            if (hook == null) hook = run.ctrlC()
            ""
        }

        assertThrows(Stopping::class.java) { Grant(TEST_APP, listOf(NOTIFICATIONS, CAMERA)).apply(run.scope) }
        hook!!.join(5_000)

        assertEquals(listOf("pm grant $TEST_APP $NOTIFICATIONS", "pm revoke $TEST_APP $NOTIFICATIONS"), run.adb.commands.filter { it.startsWith("pm ") })
    }

    @Test
    fun aGrantThatFailedIsTakenBackOnlyWhenItReachedTheDevice() {
        val granted = mutableListOf(NOTIFICATIONS)
        run.adb.on("^dumpsys package dev\\.kestrel\\.testapp$") { granted.joinToString("\n") { "        $it: granted=true, flags=[ USER_SET ]" } }
        // The grant of CAMERA takes effect, but adb loses every answer; the next grant never starts.
        run.adb.on("^pm grant dev\\.kestrel\\.testapp (\\S+)$") {
            granted.add(it.groupValues[1])
            null
        }

        assertThrows(DeviceCommandFailed::class.java) { Grant(TEST_APP, listOf(NOTIFICATIONS, CAMERA, CONTACTS)).apply(run.scope) }
        assertTrue(run.restores.runAll().isEmpty())

        assertEquals(listOf("pm revoke $TEST_APP $CAMERA"), run.adb.commands.filter { it.startsWith("pm revoke") })
    }

    @Test
    fun kestrelsAccessesAreTheKeptSetGivenWhenMissingAndNeverTakenBack() {
        for (device in listOf(run, FakeRun(DeviceKind.A71))) {
            device.adb.granted["dev.kestrel"] = mutableSetOf(IMAGES)

            assertEquals(SetupResult.Ready, KeptPermissions.MEDIA.apply(device.scope))
            assertEquals(SetupResult.Ready, KeptPermissions.MEDIA.apply(device.scope))
            assertEquals(SetupResult.Ready, KeptPermissions.NOTIFICATIONS.apply(device.scope))
            assertEquals(SetupResult.Ready, KeptPermissions.NOTIFICATIONS.apply(device.scope))

            // Only the owner's set: no READ_MEDIA_VISUAL_USER_SELECTED on API 37 either, and no restore, whose revoke or
            // disallow_listener would end Kestrel's process.
            assertEquals(0, device.restores.size)
            assertEquals(
                listOf("pm grant dev.kestrel $VIDEO", "cmd notification allow_listener ${EvalAccess.NOTIFICATION_LISTENER.id}"),
                device.adb.commands.filter { it.startsWith("pm ") || it.startsWith("cmd notification") },
            )
            assertEquals(setOf(IMAGES, VIDEO), device.adb.granted["dev.kestrel"])
        }

        // A kept access that Android does not give fails the run's setup, with what gives it.
        run.adb.refusedGrants += SMS
        val message = assertThrows(IllegalStateException::class.java) { KeptPermissions(listOf(EvalAccess.READ_SMS)).apply(run.scope) }.message!!
        assertTrue(message, message.contains(SMS) && message.contains("scripts\\install.ps1"))
        assertEquals(0, run.restores.size)

        // Kestrel's permissions are given only through KeptPermissions.
        assertThrows(IllegalArgumentException::class.java) { Grant("dev.kestrel", listOf(SMS)) }
    }
    @Test
    fun afterCtrlCNoMemoryAliasIsAddedOnceTheRestoreThatRemovesItRan() {
        val keys = mutableSetOf<String>()
        run.adb.hook("AGENT_MEMORY") { extras ->
            val key = extras.getValue("key")
            when {
                extras["has"] == "true" -> if (key in keys) "has=yes" else "has=no"
                extras["add"] == "true" -> { keys.add(key); "keys=${keys.size} added=$key" }
                else -> { keys.remove(key); "keys=${keys.size}" }
            }
        }
        val (scope, hook) = ctrlCAtThePush()

        assertThrows(Stopping::class.java) { MemoryAlias("Mira", "the Telegram chat 'Saved Messages'").apply(scope) }
        hook().join(5_000)

        assertEquals(0, run.adb.hookCalls("AGENT_MEMORY").count { it["add"] == "true" })
        assertEquals(1, run.adb.hookCalls("AGENT_MEMORY").count { it["remove"] == "true" })
        assertEquals(emptySet<String>(), keys)
    }

    @Test
    fun afterCtrlCNoStepMakesItsChangeOnceItsRestoreRan() {
        run.adb.hook("AGENT_SETTINGS") { "ownerEmails=0 viberSelfChatName=unset whatsappOwnName=unset selfChatAliases=0" }
        val config = dev.kestrel.eval.config.LocalConfig.of(mapOf("gmailAddress" to "pera.test@example.com"))
        val steps = listOf(SelfChatAliases.TELEGRAM, OwnerEmails, FixtureScreen("StandInActivity"), OpenUri("https://en.wikipedia.org/wiki/Kestrel", "org.mozilla.firefox"))

        for (step in steps) {
            val (scope, hook) = ctrlCAtThePush(config)
            assertThrows(Stopping::class.java) { step.apply(scope) }
            hook().join(5_000)
        }

        assertEquals(listOf(mapOf("selfChatAliases" to ""), mapOf("ownerEmails" to "")), run.adb.hookCalls("AGENT_SETTINGS").filter { it.isNotEmpty() })
        assertTrue(run.adb.commands.none { it.startsWith("am start") })
        assertEquals(listOf("am force-stop dev.kestrel.testapp", "am force-stop org.mozilla.firefox"), run.adb.commands.filter { it.startsWith("am force-stop") })
    }

    /** A scope whose restores start their shutdown hook, as Ctrl+C does, when a step pushes its first restore; and that hook. */
    private fun ctrlCAtThePush(config: dev.kestrel.eval.config.LocalConfig = run.scope.config): Pair<RunScope, () -> Thread> {
        var hook: Thread? = null
        lateinit var restores: Restores
        restores = Restores(run.console, Redactions(config)) { thread ->
            hook = thread
            thread.start()
            while (!restores.stopping) Thread.sleep(1)
        }
        val scope = RunScope(run.device, run.hooks, restores, run.clock, run.console, config, run.scope.work, RunValues("maple heron"))
        return scope to { hook!! }
    }

    @Test
    fun aFixtureScreenStartsFreshWithItsFilledExtrasAndTheFixturesStopAfterTheRun() {
        FixturePresses.apply(run.scope)
        FixtureScreen("StandInActivity", mapOf("screen" to "inbox", "marker" to "{marker}")).apply(run.scope)
        run.adb.on("^run-as dev.kestrel.testapp sh -c") { "send 1795086000000\n" }

        assertEquals(listOf("send"), FixturePresses.read(run.scope))
        assertTrue(Presses(listOf("send")).passes(run.scope, HookAnswer.EMPTY))
        assertFalse(Presses(emptyList()).passes(run.scope, HookAnswer.EMPTY))
        run.restores.runAll()
        assertEquals(
            listOf(
                "run-as dev.kestrel.testapp rm -f files/presses.log",
                "am start -W -S -n dev.kestrel.testapp/.StandInActivity --es screen 'inbox' --es marker 'maple heron'",
                "run-as dev.kestrel.testapp sh -c 'cat files/presses.log 2>/dev/null; true'",
                "run-as dev.kestrel.testapp sh -c 'cat files/presses.log 2>/dev/null; true'",
                "run-as dev.kestrel.testapp sh -c 'cat files/presses.log 2>/dev/null; true'",
                "am force-stop dev.kestrel.testapp",
                "run-as dev.kestrel.testapp rm -f files/presses.log",
            ),
            run.adb.commands,
        )
    }

    @Test
    fun aScreenTapThatIsRefusedFailsTheSetup() {
        run.adb.hook("SCREEN_TAP") { "tapped=false error=refused" }

        assertEquals("The element \"Wi-Fi hotspot\" was not tapped (refused).", assertThrows(SetupFailed::class.java) { ScreenTap("Wi-Fi hotspot").apply(run.scope) }.message)
    }

    @Test
    fun aMemoryAliasRefusesAKeyThatExistsSoThatItsRestoreNeverRemovesAFactOfTheOwners() {
        run.scope.values.name = "Ana"
        run.adb.hook("AGENT_MEMORY") { "has=yes" }

        assertThrows(SetupFailed::class.java) { MemoryAlias("{name}", "the Telegram chat 'Saved Messages'").apply(run.scope) }
        assertEquals(0, run.restores.size)
        assertEquals(listOf(mapOf("has" to "true", "key" to "Ana")), run.adb.hookCalls("AGENT_MEMORY"))

        run.adb.hookFails("AGENT_MEMORY", "unknown")
        assertThrows(SetupFailed::class.java) { MemoryAlias("{name}", "the Telegram chat 'Saved Messages'").apply(run.scope) }
        assertEquals(0, run.restores.size)
    }

    @Test
    fun aMemoryAliasIsAddedAndRemovedAndALostAddIsReadBack() {
        run.scope.values.name = "Mira"
        val keys = mutableSetOf<String>()
        run.adb.hook("AGENT_MEMORY") { extras ->
            val key = extras.getValue("key")
            when {
                extras["has"] == "true" -> if (key in keys) "has=yes" else "has=no"
                extras["add"] == "true" -> { keys.add(key); "keys=${keys.size} added=$key" }
                else -> { keys.remove(key); "keys=${keys.size}" }
            }
        }
        run.adb.loseNext("--ez add true")

        MemoryAlias("{name}", "the Telegram chat 'Saved Messages'").apply(run.scope)
        assertEquals(setOf("Mira"), keys)
        assertEquals(1, run.adb.hookCalls("AGENT_MEMORY").count { it["add"] == "true" })
        run.restores.runAll()

        assertEquals(emptySet<String>(), keys)
        assertEquals("the Telegram chat 'Saved Messages'", run.adb.hookCalls("AGENT_MEMORY").first { it["add"] == "true" }["text"])
    }

    @Test
    fun theSelfChatAliasesAreClearedAfterTheRun() {
        run.adb.hook("AGENT_SETTINGS") { "ownerEmails=0 viberSelfChatName=unset whatsappOwnName=unset selfChatAliases=1" }

        SelfChatAliases.TELEGRAM.apply(run.scope)
        run.restores.runAll()

        assertEquals(
            listOf(mapOf("selfChatAliases" to "dev.kestrel.testapp=org.telegram.messenger"), mapOf("selfChatAliases" to "")),
            run.adb.hookCalls("AGENT_SETTINGS"),
        )
    }

    @Test
    fun theOwnersAddressesAreEmptiedAgainOnlyWhenTestModeHadNone() {
        val config = dev.kestrel.eval.config.LocalConfig.of(mapOf("gmailAddress" to "pera.test@example.com", "emulatorGmailAddress" to "pera.second@example.com"))
        val empty = FakeRun(config = config)
        var count = 0
        empty.adb.hook("AGENT_SETTINGS") { extras -> extras["ownerEmails"]?.let { count = it.split(',').count { part -> part.isNotEmpty() } }; "ownerEmails=$count selfChatAliases=0" }

        OwnerEmails.apply(empty.scope)
        assertEquals(2, count)
        empty.restores.runAll()
        assertEquals(0, count)

        count = 1
        val set = FakeRun(config = config)
        set.adb.hook("AGENT_SETTINGS") { "ownerEmails=$count selfChatAliases=0" }
        OwnerEmails.apply(set.scope)
        assertEquals(0, set.restores.size)
        assertEquals(SetupResult.Untestable("corpus\\owner\\a71-config.json gives no address of the owner's"), OwnerEmails.apply(run.scope))
    }

    @Test
    fun theChecksCountOnThePhoneAndReadTheTasksAnswer() {
        run.scope.tag = "t1"
        run.adb.hook("SCREEN_HAS") { extras -> if (extras["contains"] == "maple heron") "count=1 app=dev.kestrel.testapp" else "count=0 app=dev.kestrel.testapp" }
        run.adb.hook("SELFCHAT_CHECK") { "selfchat=yes signs=telegram-title" }
        run.adb.hook("AGENT_TASK") { extras -> if (extras["recipient"] == "Saved Messages") "state=done recipient=match" else "state=done marker=1" }
        run.adb.on("^dumpsys activity activities$") { "  topResumedActivity=ActivityRecord{9f3e2b1 u0 org.mozilla.firefox/.App t12}" }
        val done = HookAnswer("state=done end=FINISHED confirmed=2 unconfirmed=0 shareItems=0")

        assertTrue(ScreenHas(packageName = Fixtures.PACKAGE, contains = "{marker}", count = 1..1).passes(run.scope, done))
        assertFalse(ScreenHas(contains = "Common kestrel").passes(run.scope, done))
        assertTrue(SelfChat.passes(run.scope, done))
        assertTrue(Foreground(setOf("org.mozilla.firefox")).passes(run.scope, done))
        assertTrue(Confirmed(min = 1).passes(run.scope, done))
        assertFalse(Confirmed(max = 0).passes(run.scope, done))
        assertTrue(ShareItems(0).passes(run.scope, done))
        assertFalse(ShareItems(0).passes(run.scope, HookAnswer("state=done")))
        assertTrue(Recipient("Saved Messages").passes(run.scope, done))
        assertTrue(ReplyHas.passes(run.scope, done))
        assertEquals(mapOf("result" to "true", "tag" to "t1", "marker" to "maple heron"), run.adb.hookCalls("AGENT_TASK").last())
        assertTrue(ReplyHas.needsTask && Recipient("x").needsTask && Confirmed().needsTask && ShareItems(0).needsTask)
        assertFalse(ScreenHas().needsTask || Foreground(emptySet()).needsTask || SelfChat.needsTask || Presses(emptyList()).needsTask)
    }

    private companion object {
        const val TEST_APP = "dev.kestrel.testapp"
        const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val CAMERA = "android.permission.CAMERA"
        const val CONTACTS = "android.permission.READ_CONTACTS"
        const val IMAGES = "android.permission.READ_MEDIA_IMAGES"
        const val VIDEO = "android.permission.READ_MEDIA_VIDEO"
        const val SMS = "android.permission.READ_SMS"
    }
}
