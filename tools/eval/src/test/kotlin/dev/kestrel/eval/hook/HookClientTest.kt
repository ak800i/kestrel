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

/** The hooks' broadcast, its answer and the hooks that read or act (design D3 of sub-project 6), against recorded answers. */
class HookClientTest {
    private val adb = FakeAdb()
    private val hooks = Hooks(HookClient(Device(adb, TestDevice("emulator-5554", DeviceKind.EMULATOR, 37), FakeClock())))

    @Test
    fun theBroadcastQuotesEveryTextAsHookPs1Does() {
        assertEquals(
            "am broadcast -n dev.kestrel/dev.kestrel.app.debug.TestHookReceiver -a dev.kestrel.hook.AGENT_MEMORY --ez add true --es key 'Ana' --es text 'the chat '\\''Saved Messages'\\'''",
            HookClient.broadcast("agent-memory", listOf(Extra.Flag("add", true), Extra.Text("key", "Ana"), Extra.Text("text", "the chat 'Saved Messages'"))),
        )
        assertEquals("--ei number 3", Extra.Number("number", 3).shell())
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
    fun anErrorCodeGivesTheReasonAndResultZeroMeansThatKestrelDidNotAnswer() {
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
