package dev.kestrel.eval.hook

import dev.kestrel.eval.task.FakeRun
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hooks that plan 6B adds (design D17 of sub-project 6): their extras, and which of them read. */
class HooksTest {
    private val run = FakeRun()

    @Test
    fun screenTapSendsAPartOfALabelALongPressAndThePackage() {
        run.adb.hook("SCREEN_TAP") { "tapped=true count=1" }

        run.hooks.screenTap(contains = "Kestrel test", long = true, packageName = "com.google.android.gm")
        run.hooks.screenTap("Send")

        assertEquals(
            listOf(mapOf("contains" to "Kestrel test", "long" to "true", "package" to "com.google.android.gm"), mapOf("label" to "Send")),
            run.adb.hookCalls("SCREEN_TAP"),
        )
    }

    @Test
    fun agentTaskAsksForDigitsAndTheAllowlistIsAReadThatIsTriedAgain() {
        run.adb.hook("AGENT_TASK") { extras -> "state=done digits=${if (extras["digits"] == "87") 1 else 0}" }
        run.adb.hook("NETWORK_ALLOWLIST") { "hosts=api.githubcopilot.com modelHosts=*.hf.co,huggingface.co" }
        run.adb.loseNext("NETWORK_ALLOWLIST")

        assertEquals("1", run.hooks.agentTask("t1", digits = "87")["digits"])
        assertEquals("*.hf.co,huggingface.co", run.hooks.networkAllowlist()["modelHosts"])
        assertEquals(2, run.adb.hookCalls("NETWORK_ALLOWLIST").size)
    }
}
