package dev.kestrel.agent.tools

import dev.kestrel.agent.observe.ObservationBuilder
import dev.kestrel.agent.observe.ObservationInput
import dev.kestrel.agent.observe.ObservationTable
import dev.kestrel.agent.observe.TestTree
import dev.kestrel.agent.port.OcrLine
import dev.kestrel.safety.AgentFreeApps
import dev.kestrel.safety.Category
import dev.kestrel.safety.Redactor
import dev.kestrel.safety.Vault
import dev.kestrel.screen.model.IntRect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Design D4 and D10 of sub-project 5: a call's arguments against its schema, its limits and the newest observation. */
class ToolArgumentsTest {
    private val vault = Vault()

    /** [1] a button, [2] a field and [3] an OCR line. */
    private val table: ObservationTable = run {
        val tree = TestTree()
        tree.add(200, "Send", clickable = true, className = "android.widget.Button")
        tree.add(1800, hint = "Message", editable = true, className = "android.widget.EditText")
        val ocr = listOf(OcrLine("Pay", IntRect(0, 1000, 200, 1100), 0.9))
        ObservationBuilder(Redactor(vault), AgentFreeApps()).build(ObservationInput(1, tree.snapshot(), "Chat", ocr = ocr)).table
    }

    private fun parse(name: String, json: String): ParsedCall = ToolArguments.parse(name, Json.parseToJsonElement(json).jsonObject, table, vault)

    private fun valid(name: String, json: String): AgentCall = (parse(name, json) as ParsedCall.Valid).call

    private fun invalid(name: String, json: String): String = (parse(name, json) as ParsedCall.Invalid).reason

    @Test
    fun aValidCallBecomesItsAgentCall() {
        assertEquals(AgentCall.Tap(1, "send it", Category.SENDING), valid("tap", """{"target": 1, "reason": "send it", "category": "sending"}"""))
        assertEquals(AgentCall.Tap(3, "pay", Category.PAYMENTS), valid("tap", """{"target": 3.0, "reason": "pay", "category": "payments", "extra": null}"""))
        assertEquals(
            AgentCall.TypeText(2, "", "clear it", Category.TYPING_REQUEST),
            valid("type_text", """{"target": 2, "text": "", "reason": "clear it", "category": "typing_request"}"""),
        )
        assertEquals(AgentCall.OpenUrl("https://example.com", Browser.DEFAULT, "read"), valid("open_url", """{"url": "https://example.com", "reason": "read"}"""))
        assertEquals(AgentCall.ListSms(null, 20), valid("list_sms", "{}"))
        // A blank optional text counts as left out (review 3 M5).
        assertEquals(AgentCall.ListSms(null, 5), valid("list_sms", """{"from": "", "limit": 5}"""))
        assertEquals(AgentCall.CreateEvent("Dentist", "2026-11-21T10:00", null, null), valid("create_event", """{"title": "Dentist", "start": "2026-11-21T10:00", "end": "", "location": " "}"""))
        assertEquals(
            AgentCall.SetAlarm(7, 30, null, listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            valid("set_alarm", """{"hour": 7, "minute": 30, "days": ["monday", "friday"]}"""),
        )
        assertEquals(
            AgentCall.Finish("Sent.", listOf(Evidence(1, null, null), Evidence(null, 4, true)), null, listOf(NoteDraft("Viber", "Send is at the bottom right."))),
            valid("finish", """{"summary": "Sent.", "evidence": [{"target": 1}, {"step": 4, "checked": true}], "notes": [{"app": "Viber", "note": "Send is at the bottom right."}]}"""),
        )
    }

    @Test
    fun anUnknownToolAndMissingOrMistypedArgumentsAreInvalid() {
        assertEquals("Kestrel has no such tool.", invalid("drag", "{}"))
        assertEquals("The argument reason is missing.", invalid("tap", """{"target": 1, "category": "reading"}"""))
        assertEquals("The argument target must be an integer.", invalid("tap", """{"target": "1", "reason": "r", "category": "reading"}"""))
        assertEquals("The argument target must be an integer.", invalid("tap", """{"target": 1.5, "reason": "r", "category": "reading"}"""))
        assertEquals(
            "The argument category must be one of: reading, typing_request, ordinary_settings, typing_screen_text, sending, calls, deleting, payments, credentials, protected.",
            invalid("tap", """{"target": 1, "reason": "r", "category": "harmless"}"""),
        )
        assertEquals("The argument text must be a string.", invalid("type_text", """{"target": 2, "text": 5, "reason": "r", "category": "typing_request"}"""))
        assertEquals("The argument question is empty.", invalid("ask_user", """{"question": " "}"""))
    }

    @Test
    fun theLimitsOfTheDescriptionsHold() {
        assertEquals("The argument reason is longer than 120 characters.", invalid("back", """{"reason": "${"x".repeat(121)}"}"""))
        assertEquals("The argument seconds must be from 1 to 10.", invalid("wait", """{"seconds": 11, "reason": "r"}"""))
        assertEquals("The argument text is longer than 600 characters.", invalid("say", """{"text": "${"x".repeat(601)}"}"""))
        assertEquals("The argument hour must be from 0 to 23.", invalid("set_alarm", """{"hour": 24, "minute": 0}"""))
        assertEquals("The argument day must be today, yesterday or YYYY-MM-DD.", invalid("find_media", """{"day": "last week"}"""))
        assertEquals("The argument start must be YYYY-MM-DDTHH:MM.", invalid("create_event", """{"title": "Dentist", "start": "2026-11-20 10:00"}"""))
        assertEquals("The argument items must hold item numbers such as m1.", invalid("share_media", """{"app": "telegram", "items": ["photo.jpg"]}"""))
        val notes = (1..4).joinToString(", ") { """{"app": "Viber", "note": "Note $it"}""" }
        assertEquals("At most 3 notes per app.", invalid("finish", """{"summary": "Done.", "evidence": [{"target": 1}], "notes": [$notes]}"""))
    }

    @Test
    fun aTargetMustBeInTheNewestObservationAndFitTheTool() {
        assertEquals("The newest observation has no [9].", invalid("tap", """{"target": 9, "reason": "r", "category": "reading"}"""))
        assertEquals("[1] is not an editable field.", invalid("type_text", """{"target": 1, "text": "Hi", "reason": "r", "category": "typing_request"}"""))
        assertEquals("[3] is an OCR line, which Kestrel can only tap.", invalid("type_text", """{"target": 3, "text": "Hi", "reason": "r", "category": "typing_request"}"""))
        assertEquals("[3] is an OCR line, which Kestrel can only tap.", invalid("long_press", """{"target": 3, "reason": "r", "category": "reading"}"""))
        assertEquals("The newest observation has no [9].", invalid("finish", """{"summary": "Done.", "evidence": [{"target": 9}]}"""))
        assertEquals("Each item of evidence needs a target or a step.", invalid("finish", """{"summary": "Done.", "evidence": [{"checked": true}]}"""))
    }

    @Test
    fun aPlaceholderOfTheVaultIsTypedOrSpokenAndRefusedEverywhereElse() {
        assertEquals("Your code is [OTP_1]", Redactor(vault).redact("Your code is 483920"))

        assertEquals(
            AgentCall.TypeText(2, "Code [OTP_1]", "type the code", Category.TYPING_SCREEN_TEXT),
            valid("type_text", """{"target": 2, "text": "Code [OTP_1]", "reason": "type the code", "category": "typing_screen_text"}"""),
        )
        assertEquals(AgentCall.Say("The code is [OTP_1]."), valid("say", """{"text": "The code is [OTP_1]."}"""))
        assertEquals(
            AgentCall.Finish("I read [OTP_1].", listOf(Evidence(1, null, null)), null, emptyList()),
            valid("finish", """{"summary": "I read [OTP_1].", "evidence": [{"target": 1}]}"""),
        )
        assertEquals(AgentCall.Fail("[OTP_1] expired."), valid("fail", """{"reason": "[OTP_1] expired."}"""))
        assertEquals("Kestrel can't use [OTP_1] there.", invalid("finish", """{"summary": "Done.", "evidence": [{"target": 1}], "notes": [{"app": "Bank", "note": "[OTP_1]"}]}"""))
        assertEquals("Kestrel can't use [OTP_1] there.", invalid("open_url", """{"url": "https://example.com/?c=[OTP_1]", "reason": "r"}"""))
        assertEquals("Kestrel can't use [OTP_1] there.", invalid("tap", """{"target": 1, "reason": "copy [OTP_1]", "category": "reading"}"""))
    }

    @Test
    fun aPlaceholderThatTheVaultDoesNotHoldIsRefusedEverywhere() {
        assertEquals("Kestrel has no [OTP_7] in this task.", invalid("say", """{"text": "The code is [OTP_7]."}"""))
        assertEquals("Kestrel has no [CARD_1] in this task.", invalid("type_text", """{"target": 2, "text": "[CARD_1]", "reason": "pay", "category": "typing_screen_text"}"""))
        assertEquals(AgentCall.Say("The code is (OTP_7)."), valid("say", """{"text": "The code is (OTP_7)."}"""))
    }

    @Test
    fun datesReadTodayYesterdayAndTheIsoForms() {
        val today = LocalDate.of(2026, 11, 20)

        assertEquals(today, Dates.day("today", today))
        assertEquals(LocalDate.of(2026, 11, 19), Dates.day(" Yesterday ", today))
        assertEquals(LocalDate.of(2026, 2, 28), Dates.day("2026-02-28", today))
        assertNull(Dates.day("2026-02-30", today))
        assertNull(Dates.day("20.11.2026", today))
        assertEquals(LocalDateTime.of(2026, 11, 20, 10, 20), Dates.dateTime("2026-11-20T10:20"))
        assertNull(Dates.dateTime("2026-11-20T25:00"))
        assertNull(Dates.dateTime("2026-11-20T10:20:00"))
    }
}
