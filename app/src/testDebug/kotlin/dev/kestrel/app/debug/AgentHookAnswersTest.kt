package dev.kestrel.app.debug

import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The answers of the agent's debug hooks (design D28 of sub-project 5): counts, kinds and matches, never words. */
class AgentHookAnswersTest {
    @Test
    fun aResultHoldsOnlyKindsCountsAndMatches() {
        val report = TaskReport(
            EndReason.FINISHED, summary = "I told Ana that you're late for maple heron.", recipients = listOf("Saved Messages"), steps = 5,
            workMillis = 41_000, credits = 0.5, inputTokens = 5000, outputTokens = 250, cacheRead = 2500, confirmed = 1, modelId = "scripted-light",
        )

        val line = AgentHookAnswers.result(report, "Sent to Saved Messages. I told Ana that you're late for maple heron.", "Maple heron", "saved messages")

        assertEquals(
            "state=done end=FINISHED steps=5 workMs=41000 credits=0.50 confirmed=1 unconfirmed=0 shareItems=0 recipient=match marker=1 " +
                "beforeTestMode=0 model=scripted-light in=5000 out=250 cacheRead=2500 cacheWrite=0",
            line,
        )
        assertFalse(line.contains("Ana"))
    }

    @Test
    fun aRecipientIsComparedOnThePhoneAndAMarkerByItsWordsInOrder() {
        assertEquals("other", AgentHookAnswers.recipient(listOf("Ana Petrović"), "Saved Messages"))
        assertEquals("match", AgentHookAnswers.recipient(listOf("Ana Petrovic"), "ana petrović"))
        assertEquals("none", AgentHookAnswers.recipient(emptyList(), "Saved Messages"))
        assertEquals("none", AgentHookAnswers.recipient(listOf("Saved Messages"), null))
        assertEquals(0, AgentHookAnswers.marker("I read you the e-mail about heron maple.", "maple heron"))
        assertEquals(1, AgentHookAnswers.marker("Kestrel test: Maple heron.", "maple heron"))
        assertEquals(0, AgentHookAnswers.marker("Kestrel test: maple heron.", null))
    }

    @Test
    fun anAnswerIsOneOfTheOwnersWordsANumberOrAText() {
        for (word in listOf("yes", "no", "confirm", "cancel", "continue")) assertEquals(word, AgentHookAnswers.words(word))
        assertEquals("12", AgentHookAnswers.words("12"))
        assertEquals("the blue one", AgentHookAnswers.words("text:the blue one"))
        assertNull(AgentHookAnswers.words("text:"))
        assertNull(AgentHookAnswers.words("yes, call"))
    }

    @Test
    fun aliasesMapPackagesAndRefuseAnythingElse() {
        assertEquals(mapOf("dev.kestrel.testapp" to "org.telegram.messenger"), AgentHookAnswers.aliases("dev.kestrel.testapp=org.telegram.messenger"))
        assertEquals(emptyMap<String, String>(), AgentHookAnswers.aliases(""))
        assertEquals(
            IllegalArgumentException::class.java,
            runCatching { AgentHookAnswers.aliases("dev.kestrel.testapp=Saved Messages") }.exceptionOrNull()?.javaClass,
        )
    }

    @Test
    fun theTreeKeepsOnlyTheSelfChatsFixedTextsAndTheTilesOfSystemUi() {
        val safe = AgentHookAnswers.FIXED_SAFE

        assertEquals("Saved Messages", AgentHookAnswers.safeLabel("Saved Messages", "org.telegram.messenger", safe))
        assertNull(AgentHookAnswers.safeLabel("Ana Petrović", "org.telegram.messenger", safe))
        assertEquals("Wi-Fi", AgentHookAnswers.safeLabel("Wi-Fi", AgentHookAnswers.SYSTEM_UI, safe))
        assertNull(AgentHookAnswers.safeLabel("Saved Messages", AgentHookAnswers.SYSTEM_UI, safe))
        assertEquals("last-seen", AgentHookAnswers.status("last seen recently"))
        assertEquals("members", AgentHookAnswers.status("3 members"))
        assertEquals("none", AgentHookAnswers.status("Ana Petrović"))
    }
}
