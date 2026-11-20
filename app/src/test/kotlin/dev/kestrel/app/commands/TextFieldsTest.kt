package dev.kestrel.app.commands

import dev.kestrel.commands.Target
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.Badge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which text field the text commands act on, and when dictation is on (design spec, section 9.7). */
class TextFieldsTest {
    @Test
    fun startEditingNamesOnlyATextField() {
        val notes = field("notes", "Notes", 300)
        val call = button("call", "Call", IntRect(400, 1400, 680, 1600))
        val shown = snapshot(notes, call)
        val numbers = ScreenView(shown, listOf(Badge(1, "notes", notes.bounds), Badge(2, "call", call.bounds)), grid = null)
        val plain = ScreenView(shown, badges = emptyList(), grid = null)

        // A number names the element of its badge, whatever it is.
        assertTrue(TextFields.namesOtherElement(numbers, Target.Number(2)))
        assertFalse(TextFields.namesOtherElement(numbers, Target.Number(1)))
        assertTrue(TextFields.namesOtherElement(plain, Target.Label("call")))
        assertFalse(TextFields.namesOtherElement(plain, Target.Label("notes")))
        // A name that nothing has gets "I can't find X", as for every command.
        assertFalse(TextFields.namesOtherElement(plain, Target.Label("subject")))
    }

    @Test
    fun onlySpeechThatReadsAsAPhoneNumberIsOne() {
        assertEquals("+447911123456", TextFields.phoneNumber("+44 7911 123456"))
        assertEquals("555-0100", TextFields.phoneNumber("555-0100."))
        assertEquals("+4479", TextFields.phoneNumber("Plus four four seven nine"))
        assertEquals("*#06#", TextFields.phoneNumber("star hash zero six hash"))
        assertNull(TextFields.phoneNumber("call"))
        assertNull(TextFields.phoneNumber("call 555 0100"))
        assertNull(TextFields.phoneNumber("five pm"))
        assertNull(TextFields.phoneNumber("plus"))
        assertNull(TextFields.phoneNumber(""))
    }

    private val app = WindowSummary(1, WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400), layer = 0)
    private val keyboard = WindowSummary(2, WindowKind.INPUT_METHOD, bounds = IntRect(0, 1600, 1080, 2400), layer = 2)

    private fun field(key: String, hint: String?, top: Int, focused: Boolean = false, password: Boolean = false, window: Int = 1) =
        ScreenElement(
            key = key,
            windowId = window,
            packageName = "dev.app",
            className = "android.widget.EditText",
            hint = hint,
            label = hint,
            bounds = IntRect(0, top, 800, top + 100),
            editable = true,
            focused = focused,
            password = password,
        )

    private fun button(key: String, label: String?, bounds: IntRect, checkable: Boolean = false, viewId: String? = null) =
        ScreenElement(
            key = key,
            windowId = 1,
            packageName = "dev.app",
            className = "android.widget.Button",
            label = label,
            bounds = bounds,
            clickable = true,
            checkable = checkable,
            viewId = viewId,
        )

    private fun snapshot(vararg elements: ScreenElement, keyboardShows: Boolean = false) =
        ScreenSnapshot(0, if (keyboardShows) listOf(keyboard, app) else listOf(app), elements.toList())

    @Test
    fun theFocusedFieldOutsideTheKeyboardIsTheOneThatCommandsActOn() {
        val notes = field("notes", "Notes", 300, focused = true)
        val keyboardField = field("emoji", "Search emoji", 1700, focused = true, window = 2)

        assertEquals(notes, TextFields.focused(snapshot(keyboardField, notes, keyboardShows = true)))
        assertNull(TextFields.focused(snapshot(field("notes", "Notes", 300))))
        assertTrue(TextFields.keyboardShows(snapshot(notes, keyboardShows = true)))
        assertFalse(TextFields.keyboardShows(snapshot(notes)))
    }

    @Test
    fun theOnlyFieldInsideAFocusedContainerCountsWhileTheKeyboardShows() {
        // Samsung Notes: the input focus is on the ScrollView that holds the note's body, and the body reports no focus.
        val scroll = ScreenElement(
            key = "scroll", windowId = 1, packageName = "dev.app", className = "android.widget.ScrollView",
            bounds = IntRect(0, 200, 1080, 1500), scrollable = true, focused = true,
        )
        val body = field("body", null, 300).copy(parent = 0)
        val title = field("title", "Title", 100)

        assertEquals(body, TextFields.focused(snapshot(scroll, body, title, keyboardShows = true)))
        // Without the keyboard, or with two fields inside the container, none counts.
        assertNull(TextFields.focused(snapshot(scroll, body, title)))
        assertNull(TextFields.focused(snapshot(scroll, body, field("second", null, 600).copy(parent = 0), keyboardShows = true)))
        // A field that has the focus itself comes first.
        val focusedTitle = title.copy(focused = true)
        assertEquals(focusedTitle, TextFields.focused(snapshot(scroll, body, focusedTitle, keyboardShows = true)))
    }

    @Test
    fun startEditingWithoutANameTakesTheFocusedTheOnlyOrTheFirstFromTheTop() {
        val low = field("low", "Notes", 900)
        val high = field("high", "Subject", 300)

        assertEquals(high, TextFields.toStart(snapshot(low, high)))
        assertEquals(low, TextFields.toStart(snapshot(low)))
        assertEquals(low.copy(focused = true), TextFields.toStart(snapshot(low.copy(focused = true), high)))
        assertNull(TextFields.toStart(snapshot(button("send", "Send", IntRect(0, 0, 200, 100)))))
    }

    @Test
    fun searchUsesASearchFieldOrASearchButtonAndNeverAPasswordOrADraft() {
        val notes = field("notes", "Notes", 300)
        val search = field("search", "Search mail", 100)
        val icon = button("icon", null, IntRect(900, 100, 1000, 200), viewId = "dev.app:id/search_button")
        val find = button("find", "Search", IntRect(0, 0, 100, 100))

        assertEquals(search, TextFields.searchField(snapshot(notes, search)))
        assertEquals(search.copy(focused = true), TextFields.searchField(snapshot(notes, search.copy(focused = true))))
        // A focused field that is no search field, such as a message, keeps its draft, unless its input searches.
        assertEquals(search, TextFields.searchField(snapshot(notes.copy(focused = true), search)))
        assertEquals(notes.copy(focused = true), TextFields.searchField(snapshot(notes.copy(focused = true)), focusedSearches = true))
        assertEquals(search, TextFields.searchField(snapshot(field("pin", "Search PIN", 500, focused = true, password = true), search)))
        assertNull(TextFields.searchField(snapshot(notes)))
        // Android reports SearchView's box as an AutoCompleteTextView; its view ID names it.
        val box = field("box", null, 100, focused = true).copy(viewId = "android:id/search_src_text")
        assertEquals(box, TextFields.searchField(snapshot(box)))
        assertEquals(icon, TextFields.searchButton(snapshot(notes, icon)))
        assertEquals(find, TextFields.searchButton(snapshot(find)))
        assertNull(TextFields.searchButton(snapshot(button("clear", "Clear search history", IntRect(0, 0, 100, 100)))))
        // A view ID that says search also names buttons that open no search field.
        for (id in listOf("search_voice_btn", "clear_search", "search_close_btn", "search_history_row")) {
            assertNull(id, TextFields.searchButton(snapshot(button(id, null, IntRect(0, 0, 100, 100), viewId = "dev.app:id/$id"))))
        }
    }

    @Test
    fun aSearchButtonOpensOnlyANewSearchFieldNeverTheFieldThatHadTheFocus() {
        val subject = field("subject", "Subject", 300, focused = true)
        val search = field("search", "Search mail", 100, focused = true)
        val notes = field("notes", "Notes", 500, focused = true)

        // The snapshot right after the tap may still show the old screen.
        assertNull(TextFields.openedSearchField(snapshot(subject), before = subject))
        assertNull(TextFields.openedSearchField(snapshot(subject), before = subject, searches = true))
        assertEquals(search, TextFields.openedSearchField(snapshot(subject.copy(focused = false), search), before = subject))
        assertEquals(search, TextFields.openedSearchField(snapshot(search), before = null))
        // A new field that is no search field, or a password field, is never typed into.
        assertNull(TextFields.openedSearchField(snapshot(notes), before = subject))
        assertEquals(notes, TextFields.openedSearchField(snapshot(notes), before = subject, searches = true))
        assertNull(TextFields.openedSearchField(snapshot(search.copy(password = true)), before = subject))
    }

    @Test
    fun aFieldKeepsItsFocusInAWindowWithoutTheInputFocusButIsNotTheFocusedOne() {
        val other = WindowSummary(3, WindowKind.APPLICATION, bounds = IntRect(0, 1200, 1080, 2400), layer = 1, focused = true)
        val top = field("top", "Top", 300, focused = true)
        val bottom = field("bottom", "Bottom", 1300, focused = true, window = 3)

        assertEquals(bottom, TextFields.focused(ScreenSnapshot(0, listOf(app, other), listOf(top, bottom))))
        assertNull(TextFields.focused(ScreenSnapshot(0, listOf(app, other), listOf(top))))
        assertEquals(top, TextFields.focused(ScreenSnapshot(0, listOf(app.copy(focused = true), other.copy(focused = false)), listOf(top, bottom))))
    }

    @Test
    fun showPasswordFindsANamedToggleOrALoneEyeIcon() {
        val password = field("password", "Password", 500, password = true)
        val named = button("show", "Show password", IntRect(0, 700, 400, 800), checkable = true)
        val far = button("far", "Show password", IntRect(0, 2000, 400, 2100))
        val eye = button("eye", null, IntRect(810, 500, 850, 600), checkable = true)
        val send = button("send", "Send", IntRect(0, 900, 200, 1000))

        assertEquals(password, TextFields.passwordField(snapshot(field("user", "User", 300), password)))
        assertEquals(named, TextFields.passwordToggle(snapshot(password, far, named, send), password))
        assertEquals(eye, TextFields.passwordToggle(snapshot(password, eye, send), password))
        // Two unnamed icons next to the field: Kestrel cannot tell which one shows the password.
        val other = button("other", null, IntRect(860, 500, 900, 600), checkable = true)
        assertNull(TextFields.passwordToggle(snapshot(password, eye, other, send), password))
        // An unnamed icon that does not toggle, such as "clear text", would empty the field.
        val clear = button("clear", null, IntRect(810, 500, 850, 600))
        val toggle = button("toggle", null, IntRect(810, 500, 850, 600), checkable = true)
        assertNull(TextFields.passwordToggle(snapshot(password, clear, send), password))
        assertEquals(toggle, TextFields.passwordToggle(snapshot(password, toggle, send), password))
    }

    @Test
    fun aFieldThatWasAPasswordFieldStaysOneUntilAnotherFieldHasTheFocus() {
        val editing = EditingState()
        val shown = field("password", "Password", 300, focused = true)
        val user = field("user", "User", 100, focused = true)

        assertFalse(editing.password(shown, password = false))
        assertTrue(editing.password(shown.copy(password = true), password = true))
        // "Show password" made it a plain field, and the page's button may hold the focus for a while.
        assertTrue(editing.password(shown, password = false))
        editing.follow(null)
        assertTrue(editing.password(shown, password = false))
        // Another text field takes the focus: the record goes.
        assertFalse(editing.password(user, password = false))
        assertFalse(editing.password(shown, password = false))
        // Seen without the focus, as by "show password", the field stays one until it has had the focus.
        assertTrue(editing.password(shown, password = true, focused = user))
        assertFalse(editing.password(user, password = false))
        assertTrue(editing.password(shown, password = false))
    }

    @Test
    fun dictationIsOnInTheFocusedFieldUntilStopEditingForItAndAChangeOfFocusEndsThat() {
        val editing = EditingState()
        val notes = field("notes", "Notes", 300, focused = true)
        val subject = field("subject", "Subject", 100, focused = true)

        assertEquals(notes, editing.dictating(snapshot(notes)))
        editing.stop(notes)
        assertNull(editing.dictating(snapshot(notes)))
        // Another field takes the focus: dictation is on there, and stays on when the focus comes back.
        assertEquals(subject, editing.dictating(snapshot(subject)))
        assertEquals(notes, editing.dictating(snapshot(notes)))
        editing.stop(notes)
        editing.resume()
        assertEquals(notes, editing.dictating(snapshot(notes)))
        assertNull(editing.dictating(snapshot(field("notes", "Notes", 300))))
        assertEquals("dev.app/notes", editing.id(notes))
    }

    @Test
    fun stopEditingHoldsWhileNoFieldHasTheFocus() {
        val editing = EditingState()
        val notes = field("notes", "Notes", 300, focused = true)

        editing.stop(notes)
        // A menu, a dialog or the shade holds the focus while the owner speaks once: the speech is not typed afterwards.
        assertNull(editing.dictating(snapshot(field("notes", "Notes", 300))))
        assertNull(editing.dictating(snapshot(notes)))
        editing.resume()
        assertEquals(notes, editing.dictating(snapshot(notes)))
    }

    @Test
    fun aPasswordGuessFromTheAppsInputHidesTheTranscriptButIsNotRemembered() {
        val editing = EditingState()
        val plain = field("user", "User", 100, focused = true)

        // The input that the app started may still be the one of its password field that had the focus just before.
        assertTrue(editing.hides(plain, typingPassword = true))
        assertFalse(editing.hides(plain, typingPassword = false))
        assertFalse(editing.password(plain, password = false))
        assertTrue(editing.hides(plain.copy(password = true), typingPassword = false))
    }
}
