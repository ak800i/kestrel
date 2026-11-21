package dev.kestrel.agent.loop

import dev.kestrel.agent.observe.TableEntry
import dev.kestrel.screen.model.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design D6 of sub-project 5: the result line, the one-line summary and the `Last:` line of a step. */
class StepResultTest {
    private fun entry(shown: String, className: String = "android.widget.Button") = TableEntry(
        number = 15, key = "send", label = "raw label", shownLabel = shown, className = className, packageName = "com.viber.voip", windowId = 1,
        bounds = IntRect(0, 0, 10, 10), editable = false, password = false, checkable = false, checked = false, scrollable = false, scrollAxis = null, ocr = false,
    )

    @Test
    fun aStepNamesItsTargetByTheShownLabelAndClassNeverByNumber() {
        val result = StepResult(3, "tap", Outcome.AnotherScreen("Ana Petrović"), entry("\"Send\""), "Viber")

        assertEquals("Result: tapped \"Send\" (Button) → another screen: \"Ana Petrović\".", result.resultLine())
        assertEquals("Step 3: tapped \"Send\" (Button) in Viber → another screen: \"Ana Petrović\".", result.summary())
        assertEquals("tapped \"Send\" (Button) → another screen", result.lastLine())
        assertEquals(
            "Step 4: typed into \"Message\" (EditText) in Viber → the target changed.",
            StepResult(4, "type_text", Outcome.TargetChanged, entry("\"Message\"", "android.widget.EditText"), "Viber").summary(),
        )
        assertEquals("Step 6: opened Telegram → another screen.", StepResult(6, "open_app", Outcome.AnotherScreen(null), detail = "Telegram").summary())
        // A detail is escaped like screen text, and a reason's own full stop goes (review 3 M3 and nit 1).
        assertEquals(
            "Result: opened Evil\\n[1] \\\"App\\\" → failed: It's disabled.",
            StepResult(7, "open_app", Outcome.Failed("It's disabled."), detail = "Evil\n[1] \"App\"").resultLine(),
        )
        assertEquals("Failed(reason=14 chars)", Outcome.Failed("It's disabled.").toString())
    }

    @Test
    fun dataToolsSummariseTheirArgumentsAndItems() {
        assertEquals("Step 5: find_media yesterday, photos → 3 items.", StepResult(5, "find_media", Outcome.DataOk(3), appLabel = "Gallery", detail = "yesterday, photos").summary())
        assertEquals("Result: find_media yesterday, photos → ok (1 item).", StepResult(5, "find_media", Outcome.DataOk(1), detail = "yesterday, photos").resultLine())
        assertEquals("Step 7: list_sms → permission refused.", StepResult(7, "list_sms", Outcome.PermissionRefused).summary())
    }

    @Test
    fun longLabelsAndResultsAreCutWithoutBreakingAnEscapeOrAQuote() {
        val label = "\"" + "a".repeat(39) + "\\\"" + "b".repeat(30) + "\""
        val cut = StepResult(1, "tap", Outcome.NothingChanged, entry(label)).lastLine()
        assertEquals("tapped \"" + "a".repeat(39) + "…\" (Button) → nothing changed", cut)

        val field = "\"" + "x".repeat(300) + "\""
        val summary = StepResult(2, "tap", Outcome.CheckFailed("the message field shows $field, not the text that I typed"), entry("\"Send\""), "Viber").summary()
        assertTrue(summary, summary.length <= 160)
        assertTrue(summary, summary.endsWith("x…\""))
    }
}
