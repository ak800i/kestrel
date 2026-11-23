package dev.kestrel.eval.task

import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.hook.HookAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each step pushes its restore before its change, and each check reads only counts and flags (design D4 of sub-project 6). */
class StepsTest {
    private val run = FakeRun()

    @Test
    fun forceStopStopsTheAppBeforeAndAfterTheRun() {
        ForceStop("org.mozilla.firefox").apply(run.scope)
        assertEquals(1, run.restores.size)

        run.restores.runAll()

        assertEquals(listOf("am force-stop org.mozilla.firefox", "am force-stop org.mozilla.firefox"), run.adb.commands)
    }

    @Test
    fun grantGivesOnlyTheMissingPermissionsAndTakesBackOnlyThose() {
        run.adb.on("^dumpsys package dev.kestrel$") { "        android.permission.READ_MEDIA_IMAGES: granted=true, flags=[ USER_SET ]" }

        Grant("dev.kestrel", Grant.MEDIA).apply(run.scope)
        run.restores.runAll()

        assertEquals(
            listOf(
                "dumpsys package dev.kestrel",
                "pm grant dev.kestrel android.permission.READ_MEDIA_VIDEO",
                "pm grant dev.kestrel android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
                "pm revoke dev.kestrel android.permission.READ_MEDIA_VIDEO",
                "pm revoke dev.kestrel android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
            ),
            run.adb.commands,
        )
    }

    @Test
    fun grantLeavesOutThePermissionThatAndroid13DoesNotKnow() {
        val a71 = FakeRun(DeviceKind.A71)

        Grant("dev.kestrel", Grant.MEDIA).apply(a71.scope)

        assertFalse(a71.adb.commands.any { it.endsWith("READ_MEDIA_VISUAL_USER_SELECTED") })
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
}
