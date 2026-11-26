package dev.kestrel.app.debug

import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.port.ModelPort
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeData
import dev.kestrel.agent.testing.FakeEyes
import dev.kestrel.agent.testing.FakeLog
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeScreen
import dev.kestrel.agent.testing.ScreenBuilder
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.agent.tools.Tools
import dev.kestrel.app.agent.AgentTexts
import dev.kestrel.conversation.goal.GoalEnvironment
import dev.kestrel.conversation.goal.RoundSetup
import dev.kestrel.conversation.goal.RoundStart
import dev.kestrel.providers.chat.ChatRequest
import dev.kestrel.providers.client.PricedResponse
import dev.kestrel.providers.profiles.ModelRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow

/**
 * The A71 session's stand-in for the phone's rounds (plan 2E's voice-task; design 14.4 of sub-project 7): every round of
 * a goal runs on fake ports, on a launcher's screen that the round only reads, and its model waits [seconds] and then
 * finishes, citing the screen's "Home". The finished round's final words are [reply] itself, whole and of any length, as
 * the answer to the owner's goal; the model's own summary and a failure's reason have limits, so the reply never goes
 * through them. So the owner can interrupt the reply, stop the goal or say a new command while it waits, with nothing on
 * the phone touched and no provider called. Any other end, such as a stop, keeps Kestrel's own words for it.
 *
 * With [handOver], the round's model first proposes a tap on the stand-in screen's "Pay" button as a payment, which the
 * safety check hands over to the owner (`HandOverKind.PAYMENT`, a hand-over that opens no page and ends only by the owner's
 * Continue or Cancel, or its 3 minutes), so that a check can answer a hand-over's card (plan 7F's Task 14, Step 8a); the
 * tap itself never runs, and after the owner's Continue the round finishes as without it.
 */
internal class StandInRound(private val seconds: Int, private val reply: String, private val handOver: Boolean = false) : GoalEnvironment {
    override suspend fun prepare(start: RoundStart): RoundSetup {
        val paying = ScriptedModel.call(Tools.TAP, PAY, null, null, "reason" to PAYING, "category" to PAYMENTS)
        val finishing = ScriptedModel.call(Tools.FINISH, null, null, null, "summary" to SUMMARY, "evidence" to listOf(mapOf("target" to mapOf("label" to HOME))))
        val model = ScriptedModel(listOfNotNull(paying.takeIf { handOver }, finishing))
        val ports = AgentPorts(
            FakeScreen(
                ScreenBuilder.screen(LAUNCHER) {
                    text(HOME, top = 100)
                    if (handOver) button(PAY, top = 400)
                },
            ),
            FakeEyes(),
            FakeData(),
            start.owner,
            Waiting(model, seconds * 1_000L),
            FakeApps(),
            FakeMemory(),
            FakeLog(),
            FakeClock(),
            start.goal,
            start.history,
        )
        return RoundSetup.Ready(ports, AgentSettings(), "task-${start.command}-standin", emptySet(), emptyFlow(), offersRoutines = false) {}
    }

    // A finished round, checked or not, answers with the reply.
    override fun sentence(report: TaskReport): String = when (report.end) {
        EndReason.FINISHED, EndReason.UNVERIFIED -> reply
        else -> AgentTexts.sentence(report, ScriptedModel.INFO.providerName)
    }

    override fun toString(): String = "StandInRound(seconds=$seconds, reply=${reply.length} characters, handOver=$handOver)"

    // The scripted model, which waits before each reply as a slow provider would.
    private class Waiting(private val inner: ModelPort, private val millis: Long) : ModelPort by inner {
        override suspend fun call(role: ModelRole, request: ChatRequest, step: Int): PricedResponse {
            delay(millis)
            return inner.call(role, request, step)
        }
    }

    companion object {
        /**
         * voice-task's answer for a stand-in round of [seconds] and a reply of [replyLength] characters, which first hands
         * the owner a payment step when [handOver]: counts and flags only, never the reply's words.
         */
        fun answer(seconds: Int, replyLength: Int, handOver: Boolean): String {
            val first = if (handOver) ", first hands the owner a payment step," else ""
            return "each goal's round now takes $seconds s$first and then answers with a reply of $replyLength characters, " +
                "until voice-task -Clear or the end of Kestrel's process"
        }

        private const val LAUNCHER = "com.android.launcher3"
        private const val HOME = "Home"
        private const val SUMMARY = "I read the reply."
        private const val PAY = "Pay"
        private const val PAYING = "pay"
        private const val PAYMENTS = "payments"
    }
}
