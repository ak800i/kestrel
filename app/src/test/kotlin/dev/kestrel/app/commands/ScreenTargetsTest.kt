package dev.kestrel.app.commands

import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.Target
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.act.ScreenPoint
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.Grid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenTargetsTest {
    private val app = WindowSummary(1, WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400), layer = 0)
    private val keyboard = WindowSummary(2, WindowKind.INPUT_METHOD, bounds = IntRect(0, 1600, 1080, 2400), layer = 2)

    private fun element(
        key: String,
        label: String?,
        bounds: IntRect,
        clickable: Boolean = true,
        parent: Int? = null,
        window: Int = 1,
        className: String = "android.widget.Button",
    ) = ScreenElement(key = key, windowId = window, parent = parent, className = className, label = label, bounds = bounds, clickable = clickable)

    private val elements = listOf(
        element("send", "Send", IntRect(100, 100, 300, 200)),
        element("later", "Send later", IntRect(400, 100, 700, 200)),
        element("row", null, IntRect(0, 300, 1080, 400), clickable = false, className = "android.widget.LinearLayout"),
        element("ana", "Message from Ana", IntRect(0, 300, 600, 400), clickable = false, parent = 2, className = "android.widget.TextView"),
        element("delete1", "Delete", IntRect(800, 300, 1000, 400), parent = 2),
        element("delete2", "Delete", IntRect(800, 500, 1000, 600)),
        element("compose", "Compose", IntRect(0, 700, 1080, 800), clickable = false, className = "android.widget.FrameLayout"),
        element("icon", null, IntRect(100, 700, 200, 800), parent = 6, className = "android.widget.ImageButton"),
        element("q", "q", IntRect(0, 1700, 100, 1800), window = 2),
        element("subscribe", "Subscribe", IntRect(100, 900, 500, 1000)).copy(checkable = true),
        element("message", "Message", IntRect(100, 1100, 900, 1200), className = "android.widget.EditText")
            .copy(editable = true, hint = "Message", text = "secret typed", focused = true),
        element("hidden", "Hidden", IntRect(0, 1700, 200, 1800)),
        element("seven", "7", IntRect(100, 1300, 200, 1400)),
        element("details", "Details", IntRect(100, 1450, 500, 1550), clickable = false).copy(actions = setOf(ElementAction.EXPAND)),
    )
    private val snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements)
    private val plain = ScreenView(snapshot, badges = emptyList(), grid = null)

    private fun keys(kind: TargetKind) = ScreenTargets.candidates(snapshot, kind).map { it.key }

    private fun decide(view: ScreenView, target: Target?, kind: TargetKind = TargetKind.TAP, cells: Boolean = false) =
        ScreenTargets.decide(view, target, kind, Strictness.NORMAL, cells)

    private fun one(key: String) = Resolution.One(ScreenTargets.chosen(snapshot.element(key)!!))

    @Test
    fun candidatesLeaveOutTheKeyboardAndWhatAWindowAboveCoversWhole() {
        val tap = keys(TargetKind.TAP)

        assertFalse(tap.contains("q"))
        assertFalse(tap.contains("hidden"))
        // A named element without anything actionable around or inside it gets a tap at its centre.
        assertTrue(tap.containsAll(listOf("send", "ana", "delete1", "icon", "message", "details")))
        // An element with an actionable element inside it is no candidate, and neither is one without a name.
        assertFalse(tap.contains("compose"))
        assertFalse(tap.contains("row"))
        assertEquals(listOf("send", "later", "delete1", "delete2", "icon", "subscribe", "message", "seven"), keys(TargetKind.BARE))
        assertEquals(listOf("subscribe"), keys(TargetKind.CHECK))
        assertEquals(listOf("message"), keys(TargetKind.EDIT))
        assertEquals(listOf("details"), keys(TargetKind.EXPAND))
    }

    @Test
    fun anElementWithoutANameTakesItsNearestLabelledAncestorsAndTypedTextIsNoName() {
        assertEquals(listOf("Compose"), ScreenTargets.names(snapshot, snapshot.element("icon")!!))
        assertEquals(listOf("Message"), ScreenTargets.names(snapshot, snapshot.element("message")!!))
    }

    @Test
    fun aLabelIsFoundAndTwoEqualOnesAsk() {
        assertEquals(one("send"), decide(plain, Target.Label("send")))
        assertEquals(one("later"), decide(plain, Target.Label("Send later")))
        assertEquals(one("icon"), decide(plain, Target.Label("compose")))
        assertEquals(
            Resolution.Several(listOf(ScreenTargets.chosen(elements[4]), ScreenTargets.chosen(elements[5]))),
            decide(plain, Target.Label("Delete")),
        )
        assertEquals(Resolution.NotFound, decide(plain, Target.Label("weather")))
    }

    @Test
    fun bareLabelsLookOnlyAtActionableElements() {
        assertEquals(one("details"), decide(plain, Target.Label("details")))
        assertEquals(Resolution.NotFound, decide(plain, Target.Label("details"), TargetKind.BARE))
    }

    @Test
    fun aNumberNamesABadgeAGridCellOrAnElementNamedWithIt() {
        val badges = listOf(Badge(1, "send", IntRect(100, 100, 300, 200)), Badge(2, "delete1", IntRect(800, 300, 1000, 400)))
        val numbered = plain.copy(badges = badges)
        assertEquals(one("delete1"), decide(numbered, Target.Number(2)))
        assertEquals(Resolution.NoSuchNumber(5), decide(numbered, Target.Number(5)))
        // A badge that a new snapshot has not caught up with.
        assertEquals(Resolution.Changed, decide(plain.copy(badges = listOf(Badge(1, "gone", IntRect(0, 0, 10, 10)))), Target.Number(1)))

        val grid = Grid(IntRect(0, 0, 1080, 2400), columns = 4)
        val gridded = numbered.copy(grid = grid)
        assertEquals(Resolution.Cell(3, grid.cell(3)), decide(gridded, Target.Number(3), cells = true))
        assertEquals(one("send"), decide(gridded, Target.Number(1), cells = false))

        // Without numbers or the grid, only an actionable element whose whole name is the number.
        assertEquals(one("seven"), decide(plain, Target.Number(7)))
        assertEquals(Resolution.NeedNumbers, decide(plain, Target.Number(8)))
        val seven = element("seven2", "7", IntRect(300, 1300, 400, 1400))
        val eight = element("eight", "8", IntRect(500, 1300, 600, 1400), clickable = false, className = "android.widget.TextView")
        val keys = plain.copy(snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements + seven + eight))
        assertEquals(Resolution.Several(listOf(ScreenTargets.chosen(elements[12]), ScreenTargets.chosen(seven))), decide(keys, Target.Number(7)))
        assertEquals(Resolution.NeedNumbers, decide(keys, Target.Number(8)))
    }

    @Test
    fun withoutATargetTheOnlyCandidateOrTheFocusedOne() {
        assertEquals(one("subscribe"), decide(plain, null, TargetKind.CHECK))
        assertEquals(one("message"), decide(plain, null, TargetKind.EDIT))
        assertEquals(Resolution.NotFound, decide(plain, null, TargetKind.DISMISS))
        // Several candidates: the focused one, and without one, all of them for "Which one?".
        val news = element("news", "News", IntRect(600, 900, 1000, 1000)).copy(checkable = true)
        val two = plain.copy(snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements + news))
        assertEquals(Resolution.Several(listOf(ScreenTargets.chosen(elements[9]), ScreenTargets.chosen(news))), decide(two, null, TargetKind.CHECK))
        val focusedNews = news.copy(focused = true)
        val focused = plain.copy(snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements + focusedNews))
        assertEquals(Resolution.One(ScreenTargets.chosen(focusedNews)), decide(focused, null, TargetKind.CHECK))
        // Without a name, "collapse" prefers the rows that can collapse, and "expand" the ones that can expand. The new row
        // lies above the fake keyboard, which covers the screen from y = 1600 down.
        val more = element("more", "More", IntRect(600, 1450, 1000, 1550), clickable = false).copy(actions = setOf(ElementAction.COLLAPSE))
        val rows = plain.copy(snapshot = ScreenSnapshot(0, listOf(keyboard, app), elements + more))
        assertEquals(Resolution.One(ScreenTargets.chosen(more)), decide(rows, null, TargetKind.COLLAPSE))
        assertEquals(one("details"), decide(rows, null, TargetKind.EXPAND))
        // The only row already is as asked: it is still the target, so that the bar can say so.
        assertEquals(one("details"), decide(plain, null, TargetKind.COLLAPSE))
    }

    @Test
    fun theChosenElementIsStillTheSameOnlyWithItsClassLabelAndMostOfItsBounds() {
        val send = snapshot.element("send")!!
        val chosen = ScreenTargets.chosen(send)

        assertTrue(ScreenTargets.same(chosen, send))
        assertTrue(ScreenTargets.same(chosen, send.copy(bounds = IntRect(120, 110, 320, 210))))
        assertFalse(ScreenTargets.same(chosen, send.copy(bounds = IntRect(100, 160, 300, 260))))
        assertFalse(ScreenTargets.same(chosen, send.copy(label = "Sent")))
        assertFalse(ScreenTargets.same(chosen, send.copy(className = "android.widget.TextView")))
        assertFalse(ScreenTargets.same(chosen, null))
    }

    @Test
    fun theSlidersAreTheCandidatesOfIncrementAndDecrementAndTheFocusedOneNeedsNoName() {
        val bar = "android.widget.SeekBar"
        val seekBar = element("seek", null, IntRect(0, 100, 1080, 200), clickable = false, className = bar).copy(slider = true)
        val progress = element("progress", null, IntRect(0, 300, 1080, 400), clickable = false, className = "android.widget.ProgressBar")
        val speed = element("speed", "Speed slider", IntRect(0, 500, 1080, 600), clickable = false, className = bar)
            .copy(slider = true, focused = true)
        val view = ScreenView(ScreenSnapshot(0, listOf(app), listOf(seekBar, progress, speed)), badges = emptyList(), grid = null)

        assertEquals(listOf("seek", "speed"), ScreenTargets.candidates(view.snapshot, TargetKind.SLIDER).map { it.key })
        assertEquals(Resolution.One(ScreenTargets.chosen(speed)), decide(view, null, TargetKind.SLIDER))
    }

    @Test
    fun whatIsNamesTheKindOfAnElementAndTheStateOfOneThatChecks() {
        val wifi = element("wifi", "Wi-Fi", IntRect(0, 0, 100, 100), className = "android.widget.Switch").copy(checkable = true, checked = true)
        val slider = element("seek", null, IntRect(0, 0, 100, 100), clickable = false, className = "android.widget.SeekBar").copy(slider = true)

        assertEquals("a button", ScreenTargets.role(snapshot.element("send")!!))
        assertEquals("a check box, off", ScreenTargets.role(snapshot.element("subscribe")!!))
        assertEquals("a switch, on", ScreenTargets.role(wifi))
        assertEquals("a text field", ScreenTargets.role(snapshot.element("message")!!))
        assertEquals("a text", ScreenTargets.role(snapshot.element("ana")!!))
        assertEquals("Number 5 is Send, a button.", CommandTexts.whatIs(5, "Send", ScreenTargets.role(snapshot.element("send")!!)))
        assertEquals("Number 5 has no name. It's a slider.", CommandTexts.whatIs(5, null, ScreenTargets.role(slider)))
    }

    @Test
    fun showCommandsForSaysWhatAnElementAllows() {
        assertEquals("You can say tap, check or uncheck.", CommandTexts.youCanSay(ScreenTargets.sayable(snapshot.element("subscribe")!!)))
        assertEquals("You can say tap or long press.", CommandTexts.youCanSay(ScreenTargets.sayable(elements[0].copy(longClickable = true))))
        assertEquals("You can say tap or expand.", CommandTexts.youCanSay(ScreenTargets.sayable(snapshot.element("details")!!)))
        assertEquals("You can say tap or start editing.", CommandTexts.youCanSay(ScreenTargets.sayable(snapshot.element("message")!!)))
    }

    @Test
    fun aRadioButtonThatIsOnCannotBeUncheckedAndOffersOnlyCheck() {
        val radio = element("fast", "Fast", IntRect(0, 0, 100, 100), className = "android.widget.RadioButton").copy(checkable = true, checked = true)
        val subscribed = snapshot.element("subscribe")!!.copy(checked = true)

        assertEquals("You can say tap or check.", CommandTexts.youCanSay(ScreenTargets.sayable(radio)))
        assertEquals(CommandTexts.CANT_UNCHECK_RADIO, ScreenTargets.cannotCheck(radio, on = false))
        assertNull(ScreenTargets.cannotCheck(radio, on = true))
        assertNull(ScreenTargets.cannotCheck(radio.copy(checked = false), on = false))
        assertNull(ScreenTargets.cannotCheck(subscribed, on = false))
        assertEquals(CommandTexts.NOT_CHECKABLE, ScreenTargets.cannotCheck(snapshot.element("send")!!, on = true))
    }

    @Test
    fun whatIsGivesEachNumberThatNamesNoElementItsOwnAnswer() {
        assertEquals(CommandResult(Outcome.FAILED, say = CommandTexts.SERVICE_OFF), WhatIs.answer(Resolution.NoScreen))
        // Shown on the bar, as act shows it.
        assertEquals(CommandResult(Outcome.FAILED, show = CommandTexts.noNumber(99)), WhatIs.answer(Resolution.NoSuchNumber(99)))
        assertEquals(CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED), WhatIs.answer(Resolution.Changed))
        assertEquals(CommandResult(Outcome.FAILED, say = CommandTexts.SHOW_NUMBERS_FIRST), WhatIs.answer(Resolution.NeedNumbers))
        assertEquals(
            CommandResult(Outcome.DONE, say = "That's square 5 of the grid.", repeatable = true),
            WhatIs.answer(Resolution.Cell(5, IntRect(0, 0, 100, 100))),
        )
        assertNull(WhatIs.answer(one("send")))
    }

    @Test
    fun aChosenElementAndAnAnswerPrintOnlyLengths() {
        assertFalse(ScreenTargets.chosen(elements[0]).toString().contains("Send"))
        assertEquals("ChosenElement(key of 3 characters, no label)", ScreenTargets.chosen(elements[2]).toString())
        assertEquals("Answer(9 characters)", ScreenCommands.Asked.Answer("number 22").toString())
        assertEquals("Answer(no answer)", ScreenCommands.Asked.Answer(null).toString())
    }

    @Test
    fun aChosenElementThatAWindowAboveNowCoversOrThatIsInTheKeyboardIsNoLongerThere() {
        val send = snapshot.element("send")!!
        assertEquals(send, check(snapshot, ScreenTargets.chosen(send)))

        // A window without anything to tap, such as a progress window, now covers the element: a finger cannot reach it.
        val cover = WindowSummary(3, WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 1000), layer = 1)
        val covered = ScreenSnapshot(0, listOf(keyboard, cover, app), elements)
        assertNull(check(covered, ScreenTargets.chosen(send)))
        assertNull(check(covered, ScreenTargets.chosen(send), Target.Label("Send")))
        assertNull(check(snapshot, ScreenTargets.chosen(snapshot.element("q")!!)))
    }

    @Test
    fun aLabelWhoseElementChangedIsDecidedAgainAndANumberOrAnAnswerIsNot() {
        // The "Send" button has moved, so its key now names nothing.
        val sendNow = element("send2", "Send", IntRect(600, 1300, 900, 1400))
        val moved = ScreenSnapshot(0, listOf(keyboard, app), listOf(sendNow) + elements.drop(1))
        val send = ScreenTargets.chosen(snapshot.element("send")!!)

        assertEquals(sendNow, check(moved, send, Target.Label("Send")))
        assertNull(check(moved, send))
        // Decided again, a label that two elements fit acts on neither.
        val deleteMoved = elements[4].copy(bounds = IntRect(800, 360, 1000, 460))
        val shifted = ScreenSnapshot(0, listOf(keyboard, app), elements.toMutableList().also { it[4] = deleteMoved })
        assertNull(check(shifted, ScreenTargets.chosen(elements[4]), Target.Label("Delete")))
    }

    @Test
    fun aClearBareLabelWhoseElementChangedIsDecidedAgainAmongTheActionableElements() {
        val send = ScreenTargets.chosen(snapshot.element("send")!!)
        val button = element("send2", "Send", IntRect(600, 1300, 900, 1400))
        val moved = ScreenSnapshot(0, listOf(keyboard, app), listOf(button) + elements.drop(1))
        assertEquals(button, check(moved, send, Target.Label("Send"), TargetKind.BARE, Strictness.STRICT))

        // Now only a text that cannot be tapped: a tap by name would tap it, a bare label does not.
        val text = button.copy(clickable = false, className = "android.widget.TextView")
        val plainText = ScreenSnapshot(0, listOf(keyboard, app), listOf(text) + elements.drop(1))
        assertEquals(text, check(plainText, send, Target.Label("Send")))
        assertNull(check(plainText, send, Target.Label("Send"), TargetKind.BARE, Strictness.STRICT))
    }

    @Test
    fun actAndBareDecideAgainOnlyALabelThatWasFoundAtOnce() {
        val found = one("send")
        assertEquals(Target.Label("Send"), ScreenTargets.again(Target.Label("Send"), found))
        // After "Which one?" the answer chose the element, so its label would decide among the same look-alikes again.
        assertNull(ScreenTargets.again(Target.Label("Delete"), decide(plain, Target.Label("Delete"))))
        assertNull(ScreenTargets.again(Target.Number(2), found))
        assertNull(ScreenTargets.again(null, found))
    }

    @Test
    fun aGestureOnAnElementStartsWhereNoWindowAboveCoversItAndNowhereWhenNothingOfItShows() {
        val delete = snapshot.element("delete2")!!
        assertEquals(ScreenPoint(900, 550), ScreenTargets.gesturePoint(snapshot, delete))

        // A popup over the row's centre: the point of the 8 by 8 grid that is nearest to the centre and below the popup.
        val popup = WindowSummary(3, WindowKind.APPLICATION, bounds = IntRect(0, 450, 1080, 560), layer = 1)
        assertEquals(ScreenPoint(912, 568), ScreenTargets.gesturePoint(ScreenSnapshot(0, listOf(keyboard, popup, app), elements), delete))

        // Two windows that each cover half of the element: a finger reaches neither half.
        val left = WindowSummary(3, WindowKind.APPLICATION, bounds = IntRect(0, 0, 900, 1000), layer = 1)
        val right = WindowSummary(4, WindowKind.APPLICATION, bounds = IntRect(900, 0, 1080, 1000), layer = 1)
        assertNull(ScreenTargets.gesturePoint(ScreenSnapshot(0, listOf(keyboard, left, right, app), elements), delete))
    }

    private fun check(
        snapshot: ScreenSnapshot,
        chosen: ChosenElement,
        again: Target.Label? = null,
        kind: TargetKind = TargetKind.TAP,
        strictness: Strictness = Strictness.NORMAL,
    ) = ScreenTargets.check(snapshot, chosen, again, kind, strictness)
}
