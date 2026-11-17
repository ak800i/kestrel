package dev.kestrel.app.listen

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class BoostWordBudgetTest {
    @Test
    fun kestrelComesFirstThenTheScreenThenTheVocabularyThenTheApps() {
        val labels = (1..45).map { "Label $it" }
        val vocabulary = (1..10).map { "Phrase $it" }

        val words = BoostWordBudget.combine(listOf("Kestrel", "Revolut", "Phone"), labels, vocabulary)

        assertEquals(listOf("Kestrel") + labels.take(40) + vocabulary.take(8) + listOf("Revolut", "Phone"), words)
    }

    @Test
    fun aPhraseCountsOnceAtItsFirstPlaceIgnoringCase() {
        val words = BoostWordBudget.combine(listOf("Kestrel", "viber"), listOf("Viber", "kestrel", "Send"), listOf("send"))

        assertEquals(listOf("Kestrel", "Viber", "Send"), words)
    }

    @Test
    fun theScreenGivesTheNamesOfItsActionableElementsFromTheTopDown() {
        val snapshot = snapshot(
            element("b", top = 300, label = "Send later", clickable = true),
            element("a", top = 100, label = "Send", contentDescription = "Send message", clickable = true),
            element("c", top = 500, suggestedName = "compose", clickable = true),
            // A text field gives its hint, never what was typed into it.
            element("d", top = 700, label = "Message", text = "Dear Ana", hint = "Message", editable = true),
        )

        assertEquals(listOf("Send", "Send message", "Send later", "compose", "Message"), BoostWordBudget.screenNames(snapshot))
    }

    @Test
    fun theScreenGivesNoNameThatIsPrivateOutOfReachOrUnfitForABoost() {
        val snapshot = snapshot(
            element("a", top = 100, label = "Password", hint = "Password", editable = true, password = true),
            element("b", top = 200, label = "Card number", clickable = true, dataSensitive = true),
            element("c", top = 300, label = "Not a button"),
            element("d", top = 400, label = "Delete", clickable = true, enabled = false),
            element("e", top = 500, label = "Keyboard key", clickable = true, windowId = KEYBOARD),
            element("f", top = 600, label = "OK", clickable = true),
            element("g", top = 700, label = "Ana Petrović See you at five", clickable = true),
            element("h", top = 800, label = "x".repeat(41), clickable = true),
            element("i", top = 900, label = "123 456", clickable = true),
        )

        assertEquals(emptyList<String>(), BoostWordBudget.screenNames(snapshot))
    }

    @Test
    fun aNameInCapitalsGoesInSmallLettersUnlessItIsShortAndSpelledOut() {
        val snapshot = snapshot(
            element("a", top = 100, label = "SEND", clickable = true),
            element("b", top = 200, label = "SHOW DIALOG", clickable = true),
            // Short names in capitals are often spoken letter by letter.
            element("c", top = 300, label = "USB", clickable = true),
            element("d", top = 400, contentDescription = "GPS", clickable = true),
            element("e", top = 500, label = "Wi-Fi", clickable = true),
        )

        assertEquals(listOf("send", "show dialog", "USB", "GPS", "Wi-Fi"), BoostWordBudget.screenNames(snapshot))
    }

    @Test
    fun theScreenGivesNoNameOfAnElementThatAWindowAboveCoversWhole() {
        val dialog = WindowSummary(id = DIALOG, kind = WindowKind.APPLICATION, bounds = IntRect(0, 800, 1080, 1400), layer = 3)
        val snapshot = ScreenSnapshot(
            takenAtMillis = 0,
            windows = listOf(dialog, WindowSummary(id = APP, kind = WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400), layer = 1)),
            elements = listOf(
                element("a", top = 100, label = "Inbox", clickable = true),
                // Behind the dialog, a finger cannot reach it, so no command can act on it either.
                element("b", top = 900, label = "Archive", clickable = true),
                element("c", top = 1000, label = "Allow", clickable = true, windowId = DIALOG),
                // Only partly behind the dialog: a finger still reaches the rest.
                element("d", top = 750, label = "Starred", clickable = true),
            ),
        )

        assertEquals(listOf("Inbox", "Starred", "Allow"), BoostWordBudget.screenNames(snapshot))
    }

    private fun snapshot(vararg elements: ScreenElement) = ScreenSnapshot(
        takenAtMillis = 0,
        windows = listOf(
            WindowSummary(id = KEYBOARD, kind = WindowKind.INPUT_METHOD, bounds = IntRect(0, 1600, 1080, 2400), layer = 2),
            WindowSummary(id = APP, kind = WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400), layer = 1),
        ),
        elements = elements.toList(),
    )

    private fun element(
        key: String,
        top: Int,
        label: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        hint: String? = null,
        suggestedName: String? = null,
        clickable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = true,
        password: Boolean = false,
        dataSensitive: Boolean = false,
        windowId: Int = APP,
    ) = ScreenElement(
        key = key,
        windowId = windowId,
        text = text,
        contentDescription = contentDescription,
        hint = hint,
        label = label,
        suggestedName = suggestedName,
        bounds = IntRect(0, top, 1080, top + 100),
        enabled = enabled,
        clickable = clickable,
        editable = editable,
        password = password,
        dataSensitive = dataSensitive,
    )

    private companion object {
        const val APP = 1
        const val KEYBOARD = 2
        const val DIALOG = 3
    }
}
