package dev.kestrel.app.agent.eyes

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.kestrel.agent.Agent
import dev.kestrel.agent.AgentPorts
import dev.kestrel.agent.AgentSettings
import dev.kestrel.agent.EndReason
import dev.kestrel.agent.TaskReport
import dev.kestrel.agent.TaskRequest
import dev.kestrel.agent.goal.NoGoal
import dev.kestrel.agent.goal.NoHistory
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.AnswerKind
import dev.kestrel.agent.port.Badge
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.port.ScreenPort
import dev.kestrel.agent.testing.FakeApps
import dev.kestrel.agent.testing.FakeClock
import dev.kestrel.agent.testing.FakeData
import dev.kestrel.agent.testing.FakeLog
import dev.kestrel.agent.testing.FakeMemory
import dev.kestrel.agent.testing.FakeOwner
import dev.kestrel.agent.testing.ScriptedModel
import dev.kestrel.app.screen.ScreenServiceRule
import dev.kestrel.app.voice.VoiceTests
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.providers.chat.ImageType
import dev.kestrel.providers.chat.Part
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.voice.models.Engine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * `look_closer`'s three steps on the device with the scripted model (design D20 of sub-project 5): OCR reads
 * `:testapp`'s canvas, the screenshot goes to the model after the owner's yes, the owner picks a number from the
 * numbers on the screen, and a tap on an OCR line taps its centre. The model and the owner are the test fixtures of
 * `:core:agent`; the eyes are Kestrel's own, with the OCR models that `scripts\push-models.ps1` copied.
 */
@RunWith(AndroidJUnit4::class)
class LookCloserTest {
    @get:Rule
    val screen = ScreenServiceRule()

    @Before
    fun needsTheModels() = VoiceTests.assumeModels(Engine.OCR)

    /** The screen as the agent sees it, through the session: snapshots, taps at a point and the numbers. */
    private class SessionScreen(private val session: ScreenSession) : ScreenPort {
        override suspend fun snapshot(): ScreenSnapshot = session.snapshots.current().snapshot

        override suspend fun act(action: ScreenAction): ActionOutcome = when (action) {
            is ScreenAction.TapAt -> when (val result = session.actions.tapAt(action.x, action.y)) {
                is ActionResult.Done -> ActionOutcome(done = true, method = result.method.name)
                is ActionResult.Failed -> ActionOutcome(done = false, reason = result.reason)
            }
            else -> ActionOutcome(done = false, reason = "This test taps only at points.")
        }

        override suspend fun resolve(intent: IntentSpec): String? = null

        override suspend fun handlers(intent: IntentSpec): Set<String> = emptySet()

        override suspend fun showBadges(badges: List<Badge>) =
            session.showBadges(badges.map { dev.kestrel.screen.overlay.Badge(it.number, "", it.bounds) })

        override suspend fun hideBadges() = session.hideBadges()

        override suspend fun waitForFront(packageName: String?, millis: Long, before: String?): Boolean = true
    }

    @Test
    fun readsTheCanvasThenSendsAScreenshotThenShowsTheNumbers() = runBlocking {
        screen.open("OcrCanvasActivity", "OCR canvas")
        val session = screen.session
        val look = ScriptedModel.call("look_closer", null, null, null, "reason" to "the canvas has no text")
        val model = ScriptedModel(
            listOf(
                look, look, look,
                ScriptedModel.call("tap", "Show details", null, null, "reason" to "open the details", "category" to "reading"),
                ScriptedModel.call("fail", null, null, null, "reason" to "The test ends here."),
            ),
            ScriptedModel.INFO.copy(lightweight = ScriptedModel.INFO.lightweight.copy(takesImages = true)),
        )
        val owner = FakeOwner()
        owner.answers += listOf(Answer.Text("yes"), Answer.Number(1))
        var numbersShown = 0
        owner.onAsk = { question -> if (question == "Which number should I use?") numbersShown = session.numberBadges.size }
        val eyes = AppEyes({ session }, { VoiceTests.graph.models }, { emptySet() }) { _, _ -> }
        val ports = AgentPorts(SessionScreen(session), eyes, FakeData(), owner, model, FakeApps(), FakeMemory(), FakeLog(), FakeClock(), NoGoal, NoHistory)
        var report: TaskReport? = null

        Agent(ports, AgentSettings()).run(TaskRequest("task-1-0c0ffee0", "Show the details on the test canvas")) { report = it }

        assertEquals(EndReason.FAILED_BY_MODEL, report?.end)
        // Step 1: OCR's lines are in the observation.
        val observation = model.requests[1].messages.filterIsInstance<ChatMessage.ToolResult>().last().text
        assertTrue(observation.contains("OCR \"Show details\""))
        // Step 2: after the owner's yes, a JPEG of at most 1,280 pixels follows the tool result.
        assertEquals(AnswerKind.SCREENSHOT, owner.asked[0].second)
        assertTrue(owner.asked[0].first.startsWith("May I send a screenshot of"))
        val picture = (model.requests[2].messages.last() as ChatMessage.User).parts.single() as Part.Image
        assertEquals(ImageType.JPEG, picture.type)
        val decoded = BitmapFactory.decodeByteArray(picture.bytes, 0, picture.bytes.size)
        assertTrue(max(decoded.width, decoded.height) <= AppEyes.MAX_SIDE)
        decoded.recycle()
        // Step 3: the numbers showed while Kestrel asked, and went afterwards.
        assertTrue(numbersShown > 0)
        assertTrue(session.numberBadges.isEmpty())
        assertTrue(model.requests[3].messages.filterIsInstance<ChatMessage.User>().any { message ->
            message.parts.filterIsInstance<Part.Text>().any { it.text.contains("The owner picked [1].") }
        } || model.requests[3].messages.filterIsInstance<ChatMessage.ToolResult>().any { it.text.contains("The owner picked [1].") })
        // The tap on the OCR line hit the drawn button.
        screen.awaitElement { it.label == "Details shown" }
        Unit
    }
}
