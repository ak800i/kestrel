package dev.kestrel.agent

import dev.kestrel.agent.goal.Checklist
import dev.kestrel.agent.goal.ChecklistItem
import dev.kestrel.agent.goal.ChecklistState
import dev.kestrel.agent.goal.HistoryData
import dev.kestrel.agent.goal.HistoryMatch
import dev.kestrel.agent.goal.HistoryQuery
import dev.kestrel.agent.goal.HistoryResult
import dev.kestrel.agent.goal.ItemState
import dev.kestrel.agent.goal.ResumeCause
import dev.kestrel.agent.goal.ResumeNote
import dev.kestrel.agent.goal.Steer
import dev.kestrel.agent.goal.SteerChannel
import dev.kestrel.agent.goal.TraceEntry
import dev.kestrel.agent.loop.ConfirmedSend
import dev.kestrel.agent.loop.Conversation
import dev.kestrel.agent.loop.HistoryText
import dev.kestrel.agent.loop.Outcome
import dev.kestrel.agent.loop.Redacted
import dev.kestrel.agent.loop.Reply
import dev.kestrel.agent.loop.ResultText
import dev.kestrel.agent.loop.Step
import dev.kestrel.agent.loop.StepResult
import dev.kestrel.agent.loop.Verdict
import dev.kestrel.agent.memory.Fact
import dev.kestrel.agent.memory.Recording
import dev.kestrel.agent.memory.RouteNote
import dev.kestrel.agent.memory.RouteStep
import dev.kestrel.agent.memory.Routine
import dev.kestrel.agent.memory.RoutineMatch
import dev.kestrel.agent.memory.RoutineStep
import dev.kestrel.agent.memory.SaveResult
import dev.kestrel.agent.memory.Slot
import dev.kestrel.agent.memory.SlotType
import dev.kestrel.agent.observe.ObservationBuilder
import dev.kestrel.agent.observe.ObservationInput
import dev.kestrel.agent.port.ActionOutcome
import dev.kestrel.agent.port.Answer
import dev.kestrel.agent.port.DataField
import dev.kestrel.agent.port.DataLine
import dev.kestrel.agent.port.DataResult
import dev.kestrel.agent.port.Expect
import dev.kestrel.agent.port.IntentExtra
import dev.kestrel.agent.port.IntentSpec
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.agent.port.ScreenAction
import dev.kestrel.agent.tools.AgentCall
import dev.kestrel.agent.tools.Tools
import dev.kestrel.providers.chat.AssistantPart
import dev.kestrel.providers.chat.ChatMessage
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.SafetySettings
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.IntRect
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

/**
 * The types of `:core:agent` that can carry the owner's words, screen text, prompts, replies, tool arguments or settings
 * print only kinds, counts and lengths (design D25; spec 13.5).
 */
class ToStringTest {
    private val marker = "MARKER-7731 ana@example.com"

    @Test
    fun noTypeOfTheAgentPrintsTheTextThatItCarries() {
        val redactor = Redactor(Vault())
        val observation = ObservationBuilder(redactor, AgentFreeApps()).build(ObservationInput(1, Screens.app("com.example", marker), marker))
        val reply = ChatMessage.Assistant(listOf(AssistantPart.ToolCall("c", Tools.SAY, buildJsonObject { put("text", marker) })))
        val step = RoutineStep(Tools.TYPE_TEXT, key = marker, label = marker, title = marker, text = marker)
        val routine = Routine(marker, listOf(marker), listOf(Slot("text", SlotType.TEXT)), listOf(step))
        val checklist = Checklist(listOf(ChecklistItem("c1", marker, ItemState.FAILED, why = marker)))
        val match = HistoryMatch(0, 1, marker, marker, listOf(marker), listOf(HistoryData(Tools.LIST_SMS, marker)))
        val values = listOf(
            TaskRequest(marker, marker, setOf(marker)),
            TaskReport(EndReason.FINISHED, detail = marker, summary = marker, ledger = listOf(marker), sends = listOf(marker), recipients = listOf(marker)),
            AgentSettings(SafetySettings(ownerEmails = listOf(marker), whatsappOwnName = marker), selfChatAliases = mapOf(marker to marker)),
            Ledger().apply { add(marker) },
            Redacted.kestrel(marker),
            ResultText("c", Tools.SAY, Redacted.kestrel(marker)),
            Step(1, reply, listOf(ResultText("c", Tools.SAY, Redacted.kestrel(marker)))),
            Conversation(marker, Tools.ALL, Redacted.kestrel(marker), Redacted.kestrel(marker), observation, marker),
            ConfirmedSend(listOf(marker), marker, share = false),
            Verdict.Missing(marker),
            Reply.Act("c", Tools.SAY, AgentCall.Say(marker), emptyList()),
            Fact(marker, marker),
            RouteNote(marker, marker, marker, marker),
            RouteStep("tap", marker),
            step,
            routine,
            Recording(marker, listOf(step), marker, 0),
            RoutineMatch(routine, mapOf("text" to marker)),
            SaveResult.NotSaved(marker),
            AgentCall.Tap(1, marker, Category.READING),
            AgentCall.AddContact(marker, marker),
            DataLine(marker, listOf(DataField("name", marker)), ref = marker),
            // The types of Tasks 6 and 7 that carry screen text, data or the owner's words.
            observation,
            observation.table,
            observation.table.entries.first(),
            Expect(marker, marker, marker, editable = true, password = false, checkable = false),
            ScreenAction.SetText(marker, Expect(marker, null, null, editable = true, password = false, checkable = false), marker),
            ActionOutcome(done = false, reason = marker),
            DataResult.Ok(listOf(DataLine(marker, listOf(DataField("name", marker)))), note = marker),
            DataResult.Opens(IntentSpec("android.intent.action.SEND", marker, marker, extras = mapOf("text" to IntentExtra.Text(marker), "items" to IntentExtra.ContentUris(listOf(marker))), type = marker, readable = listOf(marker)), marker),
            DataResult.Failed(marker),
            DataLine(marker, listOf(DataField("name", marker))),
            DataField("name", marker),
            Answer.Text(marker),
            OcrLine(marker, IntRect(0, 0, 10, 10), 0.9),
            IntentSpec("android.intent.action.VIEW", marker, marker),
            StepResult(1, Tools.TAP, Outcome.AnotherScreen(marker), observation.table.entries.first(), marker, marker),
            Outcome.AnotherScreen(marker),
            Outcome.CheckFailed(marker),
            // Plan 7A's types, which carry the owner's words, Kestrel's lines, data and the model's checklist.
            TaskRequest(marker, marker, setOf(marker), Redacted.kestrel(marker), listOf(marker), setOf(Category.SENDING), checklist, ResumeNote(ResumeCause.RESTART, listOf(marker))),
            Steer(marker, SteerChannel.TYPED),
            TraceEntry.Requested(marker),
            TraceEntry.Said(marker),
            TraceEntry.Asked(marker),
            TraceEntry.Answered(marker),
            TraceEntry.Data(Tools.LIST_SMS, 2, marker),
            TraceEntry.AppUsed(marker, marker),
            TraceEntry.Did(marker),
            checklist,
            checklist.items.single(),
            ChecklistState.atStart(checklist),
            ResumeNote(ResumeCause.LIMIT, listOf(marker), listOf(Steer(marker, SteerChannel.TYPED))),
            HistoryQuery(marker),
            HistoryData(Tools.LIST_SMS, marker),
            match,
            HistoryResult(listOf(match)),
            AgentCall.SearchConversations(marker, null, null),
            HistoryText.Shown(Redacted.kestrel(marker), 1),
        )

        for (value in values) assertFalse(value.toString(), value.toString().contains("MARKER") || value.toString().contains("@example"))
    }

    // A search's days say when the owner did something, so they are arguments that no print shows.
    @Test
    fun aSearchPrintsWhetherItHasDaysNotTheDays() {
        val call = AgentCall.SearchConversations(marker, LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 20))

        assertFalse(call.toString(), call.toString().contains("2026") || call.toString().contains("MARKER"))
    }
}
