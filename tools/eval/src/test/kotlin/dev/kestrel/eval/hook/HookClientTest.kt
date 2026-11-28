package dev.kestrel.eval.hook

import dev.kestrel.eval.device.Device
import dev.kestrel.eval.device.DeviceKind
import dev.kestrel.eval.device.FakeAdb
import dev.kestrel.eval.device.FakeClock
import dev.kestrel.eval.device.TestDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hooks' transport, `content call` (plan 7G's amendment "The hooks' transport"), its answer and the hooks that read or
 * act (design D3 of sub-project 6). The outputs in [theAnswersOfContentCallOnKestrelApi37] are real ones of `content call`
 * on kestrel-api37, captured once with TestHookProvider, as are [FakeAdb.DEAD_CALL] and [FakeAdb.REFUSED_CALLER].
 */
class HookClientTest {
    private val adb = FakeAdb()
    private val hooks = Hooks(HookClient(Device(adb, TestDevice("emulator-5554", DeviceKind.EMULATOR, 37), FakeClock())))

    @Test
    fun theCallQuotesEveryTextAsHookPs1Does() {
        assertEquals(
            "content call --uri content://dev.kestrel.hooks --method AGENT_MEMORY --extra 'add:b:true' --extra 'key:s:Ana' " +
                "--extra 'text:s:the chat '\\''Saved Messages'\\'''",
            HookClient.command("agent-memory", listOf(Extra.Flag("add", true), Extra.Text("key", "Ana"), Extra.Text("text", "the chat 'Saved Messages'"))),
        )
        assertEquals("--extra 'number:i:3'", Extra.Number("number", 3).shell())
        // On kestrel-api37, voice-state with this extra answered "error: no transcript has the tag it's a:b\c".
        assertEquals("--extra 'tag:s:it'\\''s a\\:b\\\\c'", Extra.Text("tag", "it's a:b\\c").shell())
    }

    @Test
    fun theAnswersOfContentCallOnKestrelApi37() {
        assertEquals(
            "standin=off profile=copilot copilot=1 id=profile-b568f281-684a-42db-8f75-405ccca77931 credential=present",
            HookClient.answer(
                "provider-standin",
                "Result: Bundle[{code=0, text=standin=off profile=copilot copilot=1 id=profile-b568f281-684a-42db-8f75-405ccca77931 credential=present}]",
            ).text,
        )
        assertEquals(
            "error: add --es do with one of tap, touch, speak, focus, refuse, refuse-off, send-later or stop-typed",
            assertThrows(HookError::class.java) {
                HookClient.answer(
                    "goal-control",
                    "Result: Bundle[{code=1, text=error: add --es do with one of tap, touch, speak, focus, refuse, refuse-off, send-later or stop-typed}]",
                )
            }.message,
        )
        // The text is all that stands between "text=" and the last "}]", also the format's own marks and line breaks.
        assertEquals(
            "error: unknown command 'X}], code=0, text=Y'",
            assertThrows(HookError::class.java) { HookClient.answer("x", "Result: Bundle[{code=1, text=error: unknown command 'X}], code=0, text=Y'}]") }.message,
        )
        assertEquals(
            "error: unknown command 'LINE1\nLINE2'",
            assertThrows(HookError::class.java) { HookClient.answer("x", "Result: Bundle[{code=1, text=error: unknown command 'LINE1\nLINE2'}]") }.message,
        )
        assertThrows(HookSilent::class.java) { HookClient.answer("state", FakeAdb.NO_HOOKS.trimEnd()) }
    }

    // Plan 7G's review of the hooks' transport, I2: only code 1 is Kestrel's refusal. A call that failed on its way, as when
    // Kestrel's process ended during it, is lost, because Kestrel may have acted; a refused caller is its own failure.
    @Test
    fun theFailuresOfContentCallOnKestrelApi37AreLostOrARefusedCallerNeverKestrelsRefusal() {
        assertEquals(
            "The answer of agent-answer was lost: android.os.DeadObjectException",
            assertThrows(HookLost::class.java) { HookClient.answer("agent-answer", FakeAdb.DEAD_CALL.trimEnd()) }.message,
        )
        assertEquals(
            "Kestrel refused the caller of state: java.lang.SecurityException: Permission Denial: Do not have permission in call " +
                "getContentProviderExternal() from pid=4343, uid=10353 requires android.permission.ACCESS_CONTENT_PROVIDERS_EXTERNALLY. " +
                "Only adb's shell (uid 2000) and root (uid 0) may use its test hooks.",
            assertThrows(HookCallerRefused::class.java) { HookClient.answer("state", FakeAdb.REFUSED_CALLER.trimEnd()) }.message,
        )
        assertEquals(
            "The answer of state was lost: its answer had the unknown code 2: x",
            assertThrows(HookLost::class.java) { HookClient.answer("state", "Result: Bundle[{code=2, text=x}]") }.message,
        )
        assertEquals("The answer of state was lost: Result: null", assertThrows(HookLost::class.java) { HookClient.answer("state", "Result: null") }.message)
    }

    @Test
    fun aHookThatReadsIsAskedAgainAfterADeadCallAndOneThatActsIsLost() {
        adb.hook("PROVIDER_STANDIN") { "standin=off profile=copilot copilot=1 id=profile-1 credential=present" }
        adb.hook("SCREEN_TAP") { "tapped=true count=1" }
        adb.dieNext("--method PROVIDER_STANDIN")
        adb.dieNext("--method SCREEN_TAP")

        assertEquals("copilot", hooks.providerStandin().profile)
        assertThrows(HookLost::class.java) { hooks.screenTap("Send") }
        assertEquals(2, adb.hookCalls("PROVIDER_STANDIN").size)
        assertEquals(1, adb.hookCalls("SCREEN_TAP").size)
    }

    @Test
    fun anAnswerGivesItsPairsAndTheExtrasReachTheHook() {
        adb.hook("AGENT_TASK") { "state=done end=FINISHED steps=5 credits=0.50" }

        val answer = hooks.agentTask("t1", marker = "maple heron")

        assertEquals("FINISHED", answer["end"])
        assertEquals(5, answer.int("steps"))
        assertEquals(0, answer.int("unconfirmed"))
        assertEquals(listOf(mapOf("result" to "true", "tag" to "t1", "marker" to "maple heron")), adb.hookCalls("AGENT_TASK"))
    }

    @Test
    fun aWordWithoutAValueStaysOnlyInTheText() {
        adb.hook("AGENT_ANSWER") { "answered tag=t1 asking=confirm" }

        val answer = hooks.agentAnswer("t1", "confirm", "sending")

        assertEquals(mapOf("tag" to "t1", "asking" to "confirm"), answer.pairs)
        assertEquals(mapOf("tag" to "t1", "answer" to "confirm", "confirming" to "sending"), adb.hookCalls("AGENT_ANSWER").single())
    }

    @Test
    fun anErrorCodeGivesTheReasonAndAMissingProviderMeansThatKestrelDidNotAnswer() {
        adb.hookFails("AGENT_STATE", "no task has the tag t9")

        assertEquals("no task has the tag t9", assertThrows(HookError::class.java) { hooks.agentState("t9") }.message)
        assertThrows(HookSilent::class.java) { hooks.state() }
    }

    @Test
    fun aHookThatReadsIsTriedAgainAndOneThatActsRunsOnce() {
        adb.hook("PROVIDER_STANDIN") { "standin=off profile=copilot copilot=1 id=profile-1 credential=present" }
        adb.hook("SCREEN_TAP") { "tapped=true count=1" }
        adb.loseNext("PROVIDER_STANDIN")
        adb.loseNext("SCREEN_TAP")

        assertEquals("copilot", hooks.providerStandin().profile)
        assertThrows(HookLost::class.java) { hooks.screenTap("Send") }
        assertEquals(2, adb.hookCalls("PROVIDER_STANDIN").size)
        assertEquals(1, adb.hookCalls("SCREEN_TAP").size)
    }

    @Test
    fun theMemoryAnswersOnlyWhetherAKeyExists() {
        adb.hook("AGENT_MEMORY") { extras -> if (extras["key"] == "Ana") "has=yes" else "has=no" }

        assertTrue(hooks.agentMemoryHas("Ana"))
        assertFalse(hooks.agentMemoryHas("Mira"))
        assertEquals(mapOf("has" to "true", "key" to "Ana"), adb.hookCalls("AGENT_MEMORY").first())
    }

    @Test
    fun aTranscriptCarriesItsTagAndItsLiveFlagOnlyWhenLive() {
        adb.hook("VOICE") { "sent" }

        hooks.voiceHeard("Kestrel, turn on hotspot", "t1")
        hooks.voiceHeard("Kestrel, turn on hotspot", "t2", live = true, model = "gpt-6-luna")

        assertEquals(
            listOf(
                mapOf("event" to "heard", "text" to "Kestrel, turn on hotspot", "tag" to "t1"),
                mapOf("event" to "heard", "text" to "Kestrel, turn on hotspot", "tag" to "t2", "live" to "true", "model" to "gpt-6-luna"),
            ),
            adb.hookCalls("VOICE"),
        )
    }
}
