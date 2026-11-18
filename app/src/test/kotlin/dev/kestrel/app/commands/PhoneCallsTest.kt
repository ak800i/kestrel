package dev.kestrel.app.commands

import android.Manifest
import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.commands.CallTarget
import dev.kestrel.commands.Command
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Edge
import dev.kestrel.commands.Target
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.phone.Contact
import dev.kestrel.commands.phone.PhoneEntry
import dev.kestrel.commands.phone.PhoneNumbers
import dev.kestrel.screen.model.ElementAction
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.model.WindowSummary
import dev.kestrel.screen.overlay.Badge
import dev.kestrel.screen.overlay.Grid
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCallsTest {
    private companion object {
        const val DIALER = "com.google.android.dialer"
    }

    private val mobile = PhoneEntry("064 123 4567", "mobile", superPrimary = false)
    private val work = PhoneEntry("011 234 5678", "work", superPrimary = false)
    private val ana = Contact(1, listOf("Ana Petrović"), listOf(mobile, work), starred = false)
    private val anaJ = Contact(2, listOf("Ana Jovanović"), listOf(mobile.copy(number = "065 765 4321")), starred = false)

    /** The phone app: what Kestrel did with it; [started] is what the call state shows after a press. */
    private class Phone(var button: Boolean = true, var pressed: Pressed = Pressed.CALLING, var started: Boolean? = true) : Dialer {
        val did = mutableListOf<String>()

        // 0641111111 stands for an emergency number, which Android would report.
        override fun rule(digits: String) = AndroidDialer.ruleFor(digits) { it == "0641111111" }

        override suspend fun open(digits: String): Boolean {
            did += "open $digits"
            return true
        }

        override suspend fun awaitCallButton() = button

        override suspend fun press(digits: String): Pressed {
            did += "press $digits"
            return pressed
        }

        override suspend fun callStarted() = started
    }

    /** The owner: answers the questions in order, and lets the countdown run out unless [stops]. */
    private class Owner(private vararg val answers: String?, val stops: Boolean = false) : CommandConversation {
        val said = mutableListOf<String>()
        val asked = mutableListOf<String>()
        val counted = mutableListOf<String>()
        private var next = 0

        override suspend fun say(text: String) {
            said += text
        }

        override suspend fun ask(question: String): String? {
            asked += question
            return answers[next++]
        }

        override fun keep(text: String) = Unit

        override suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean {
            counted += text((millis / 1_000).toInt())
            return !stops
        }
    }

    private var allowed = true
    private val opened = mutableListOf<Pair<SettingsPage?, String?>>()
    private val phone = Phone()

    private fun calls(vararg contacts: Contact, policy: EmergencyPolicy = EmergencyPolicy.CONFIRM): CallCommands {
        val source = object : ContactsSource {
            override fun read() = contacts.toList()

            override fun watch(changed: () -> Unit) = AutoCloseable {}
        }
        return CallCommands(ContactsReader({ allowed }, source) { _, _ -> }, phone, { page, permission -> opened += page to permission }, policy)
    }

    @Test
    fun aNumberIsAnnouncedDigitByDigitCountedDownAndCalled() = runTest {
        val owner = Owner()

        val result = calls().call(CallTarget.Number("0641234567"), owner)

        assertEquals(CommandResult(Outcome.DONE), result)
        assertEquals(listOf("Calling 0 6 4 1 2 3 4 5 6 7."), owner.said)
        assertEquals(listOf("Calling 0 6 4 1 2 3 4 5 6 7, in 4"), owner.counted)
        assertEquals(listOf("open 0641234567", "press 0641234567"), phone.did)
    }

    @Test
    fun aStoppedCountdownLeavesTheNumberInThePhoneApp() = runTest {
        val result = calls().call(CallTarget.Number("0641234567"), Owner(stops = true))

        assertEquals(CommandResult(Outcome.CANCELLED), result)
        assertEquals(listOf("open 0641234567"), phone.did)
    }

    @Test
    fun aShortOrCodeNumberOnlyOpensThePhoneApp() = runTest {
        val owner = Owner()

        val result = calls().call(CallTarget.Number("*#06#"), owner)

        assertEquals(CommandResult(Outcome.DONE, say = "I opened the phone app with star hash 0 6 hash. Tap Call yourself."), result)
        assertEquals(listOf("open *#06#"), phone.did)
        assertEquals(emptyList<String>(), owner.counted)
        assertEquals(DialRule.TAP_YOURSELF, AndroidDialer.ruleFor("1985") { false })
        assertEquals(DialRule.CALL, AndroidDialer.ruleFor("0641234567,12") { false })
    }

    @Test
    fun anEmergencyNumberIsPressedOnlyAfterYesCallAndNeverThroughTheCountdown() = runTest {
        val yes = Owner("Yes, call.")
        assertEquals(CommandResult(Outcome.DONE), calls().call(CallTarget.Number("0641111111"), yes))
        assertEquals(listOf("Calling 0 6 4 1 1 1 1 1 1 1."), yes.said)
        assertEquals(listOf(PhoneCommandTexts.EMERGENCY_QUESTION), yes.asked)
        assertEquals(emptyList<String>(), yes.counted)
        assertEquals(listOf("open 0641111111", "press 0641111111"), phone.did)

        // Any other answer, and no answer (the question's timer or a touch), cancel; Android's check failing counts as an
        // emergency number.
        for (answer in listOf("no", "yes", null)) {
            assertEquals(CommandResult(Outcome.CANCELLED), calls().call(CallTarget.Number("0641111111"), Owner(answer)))
        }
        assertEquals(DialRule.EMERGENCY, AndroidDialer.ruleFor("0641234567") { null })
        assertEquals(DialRule.EMERGENCY, AndroidDialer.ruleFor("112") { it == "112" })

        // Under TOUCH_ONLY, Kestrel only opens the phone app and asks nothing.
        val touch = Owner()
        assertEquals(
            CommandResult(Outcome.DONE, say = "I opened the phone app with 0 6 4 1 1 1 1 1 1 1. Tap Call yourself."),
            calls(policy = EmergencyPolicy.TOUCH_ONLY).call(CallTarget.Number("0641111111"), touch),
        )
        assertEquals(emptyList<String>(), touch.asked)
        assertEquals("press 0641111111", phone.did.single { it.startsWith("press") })
    }

    @Test
    fun onlyYesCallGoesOnAndTouchOnlyRefusesWithoutAsking() = runTest {
        for (answer in listOf("yes, call", "Yes call.", "yes call Kestrel")) {
            assertEquals(answer, null, EmergencyGuard.confirm(EmergencyPolicy.CONFIRM, Owner(answer)))
        }
        // An answer that starts with the name is a request of Kestrel's own, which plan 3A's rules never pass as an answer.
        for (answer in listOf("yes", "call", "yes please call", "no", "yes, call 112", "Kestrel, yes, call", "", null)) {
            assertEquals(answer, CommandResult(Outcome.CANCELLED), EmergencyGuard.confirm(EmergencyPolicy.CONFIRM, Owner(answer)))
        }
        val owner = Owner()
        assertEquals(
            CommandResult(Outcome.FAILED, say = "Tap it yourself for an emergency call."),
            EmergencyGuard.confirm(EmergencyPolicy.TOUCH_ONLY, owner),
        )
        assertEquals(emptyList<String>(), owner.asked)
    }

    @Test
    fun withoutACallButtonOrWithARefusedPressTheOwnerTapsCall() = runTest {
        phone.button = false
        assertEquals(
            CommandResult(Outcome.FAILED, say = PhoneCommandTexts.OPENED_TAP_CALL),
            calls().call(CallTarget.Number("0641234567"), Owner()),
        )
        phone.button = true
        phone.pressed = Pressed.REFUSED
        assertEquals(
            CommandResult(Outcome.FAILED, say = PhoneCommandTexts.COULD_NOT_PRESS),
            calls().call(CallTarget.Number("0641234567"), Owner()),
        )
        phone.pressed = Pressed.CHOOSE_SIM
        assertEquals(CommandResult(Outcome.DONE, show = PhoneCommandTexts.CHOOSE_SIM), calls().call(CallTarget.Number("0641234567"), Owner()))
    }

    @Test
    fun aCallThatDoesNotStartWithinFiveSecondsOfThePressFailsAndKestrelSaysSo() = runTest {
        // Google's phone app took the click and called an empty number, which Android refused without a word.
        phone.started = false
        assertEquals(
            CommandResult(Outcome.FAILED, say = "The call didn't start. Say it again."),
            calls().call(CallTarget.Number("0641234567"), Owner()),
        )
        // After "yes, call" too.
        assertEquals(
            CommandResult(Outcome.FAILED, say = "The call didn't start. Say it again."),
            calls().call(CallTarget.Number("0641111111"), Owner("yes, call")),
        )
        // Without READ_PHONE_STATE Kestrel cannot see the call state, so the press counts as before.
        phone.started = null
        assertEquals(CommandResult(Outcome.DONE), calls().call(CallTarget.Number("0641234567"), Owner()))
        // A SIM chooser waits for the owner, so no call has started yet.
        phone.started = false
        phone.pressed = Pressed.CHOOSE_SIM
        assertEquals(CommandResult(Outcome.DONE, show = PhoneCommandTexts.CHOOSE_SIM), calls().call(CallTarget.Number("0641234567"), Owner()))
    }

    @Test
    fun aFieldThatShowsTheNumberIsEmptiedBeforeTheDialThroughItsOwnActionsAndNoOtherIs() = runTest {
        val fields = AndroidDialer.numberFields(dialer("064 123 4567"), setOf(DIALER))

        // The phone app's number field, and what Kestrel did with it.
        class Field(var text: String?, val takesText: Boolean = true, val longPressEmpties: Boolean = true) {
            val did = mutableListOf<String>()

            suspend fun empty(digits: String, readable: Boolean = true) = AndroidDialer.emptyFields(
                fields,
                digits,
                read = { if (readable) mapOf("digits" to text) else emptyMap() },
                clear = {
                    did += "set text"
                    takesText.also { if (it) text = "" }
                },
                longDelete = {
                    did += "long press delete"
                    longPressEmpties.also { if (it) text = "" }
                },
                delete = {
                    did += "delete"
                    text = text?.dropLast(1)
                    true
                },
            )
        }

        // The field shows the number of the DIAL, as the phone app formats it: its own set-text action empties it.
        val same = Field("064 123 4567")
        assertTrue(same.empty("0641234567"))
        assertEquals("", same.text)
        assertEquals(listOf("set text"), same.did)
        // Another number, an empty field and one that Kestrel cannot read stay as they are.
        for (field in listOf(Field("064 123 4568"), Field(null), Field("+064 123 4567"))) {
            assertTrue(field.empty("0641234567"))
            assertEquals(emptyList<String>(), field.did)
        }
        val unread = Field("064 123 4567")
        unread.empty("0641234567", readable = false)
        assertEquals(emptyList<String>(), unread.did)
        // A field without a set-text action: a long press on the delete key, and without that, the key once for each digit.
        val pressed = Field("112", takesText = false)
        assertTrue(pressed.empty("112"))
        assertEquals(listOf("set text", "long press delete"), pressed.did)
        val keys = Field("112", takesText = false, longPressEmpties = false)
        assertTrue(keys.empty("112"))
        assertEquals("", keys.text)
        assertEquals(listOf("set text", "long press delete", "delete", "delete", "delete"), keys.did)
    }

    @Test
    fun theDeleteKeyThatEmptiesTheFieldComesOnlyFromTheFieldsWindow() {
        val phone = dialer("064 123 4567")
        // A call-history dialog of the phone app, above the dial pad, with its own "Delete" button listed first.
        val dialog = WindowSummary(2, WindowKind.APPLICATION, packageName = DIALER, bounds = screen)
        val history = element("history_delete", 2, IntRect(100, 900, 980, 1000), text = "Delete")
        val snapshot = ScreenSnapshot(0, phone.windows + dialog, listOf(history) + phone.elements)
        val fields = AndroidDialer.numberFields(snapshot, setOf(DIALER))

        assertEquals("delete", AndroidDialer.deleteKey(snapshot, DIALER, fields)?.key)
        assertNull(AndroidDialer.deleteKey(ScreenSnapshot(0, snapshot.windows, listOf(history) + fields), DIALER, fields))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theCallCountsAsStartedOnlyWhenTheCallStateShowsAnActiveCallWithinFiveSeconds() = runTest {
        // The call monitor of Kestrel's modes Ringing and In call: the state at its start, then each change.
        class Monitor(private val atStart: CallState, private val fails: Boolean = false) : CallMonitor {
            var report: ((CallState) -> Unit)? = null
            var stopped = false

            override fun start(report: (CallState) -> Unit) {
                if (fails) throw SecurityException()
                this.report = report
                report(atStart)
            }

            override fun stop() {
                stopped = true
            }
        }

        val later = Monitor(CallState.IDLE)
        val started = async { AndroidDialer.awaitCall(later) }
        advanceTimeBy(2_000)
        later.report?.invoke(CallState.ACTIVE)
        assertEquals(true, started.await())
        assertTrue(later.stopped)
        assertEquals(true, AndroidDialer.awaitCall(Monitor(CallState.ACTIVE)))
        // No call within 5 s, also while another call rings.
        val none = Monitor(CallState.RINGING)
        val waited = testScheduler.currentTime
        assertEquals(false, AndroidDialer.awaitCall(none))
        assertEquals(5_000L, testScheduler.currentTime - waited)
        assertTrue(none.stopped)
        // A monitor that cannot start: Kestrel cannot tell.
        assertNull(AndroidDialer.awaitCall(Monitor(CallState.IDLE, fails = true)))
    }

    @Test
    fun withoutTheContactsPermissionKestrelSaysWhyAndOpensItsSettingsToAskForIt() = runTest {
        allowed = false

        val result = calls(ana).call(CallTarget.Name("Ana", null), Owner())

        assertEquals(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NEED_CONTACTS), result)
        assertEquals(listOf<Pair<SettingsPage?, String?>>(null to Manifest.permission.READ_CONTACTS), opened)
        assertEquals(emptyList<String>(), phone.did)
    }

    @Test
    fun aNameWithTheKindIsCalledWithItsKind() = runTest {
        val owner = Owner()

        calls(ana, anaJ).call(CallTarget.Name("Ana Petrovic", "mobile"), owner)

        assertEquals(listOf("Calling Ana Petrović, mobile."), owner.said)
        assertEquals(listOf("open 0641234567", "press 0641234567"), phone.did)
    }

    @Test
    fun twoContactsAskWhichOneAndSeveralNumbersAskWhichNumber() = runTest {
        val owner = Owner("2", "work")

        calls(ana, anaJ, Contact(3, listOf("Marko"), listOf(mobile), starred = false)).call(CallTarget.Name("Ana", null), owner)

        assertEquals(listOf("Which one? 1, Ana Jovanović. 2, Ana Petrović.", "Which number? Mobile or work?"), owner.asked)
        assertEquals(listOf("Calling Ana Petrović, work."), owner.said)
        assertEquals(listOf("open 0112345678", "press 0112345678"), phone.did)

        // "Cell" is the mobile number.
        calls(ana).call(CallTarget.Name("Ana Petrović", null), Owner("cell"))
        assertEquals("open 0641234567", phone.did[2])
    }

    @Test
    fun aStoredPauseOrExtensionStaysForThePhoneAppAndAWordIsNeverTurnedIntoDigits() = runTest {
        val office = Contact(4, listOf("Office"), listOf(PhoneEntry("011 234 5678 ext. 12", "work", superPrimary = false)), starred = false)
        val shop = Contact(5, listOf("Flower Shop"), listOf(PhoneEntry("1-800-FLOWERS", "work", superPrimary = false)), starred = false)

        calls(office).call(CallTarget.Name("Office", null), Owner())
        val result = calls(shop).call(CallTarget.Name("Flower Shop", null), Owner())

        assertEquals(listOf("open 0112345678,12", "press 0112345678,12", "open 1-800-FLOWERS"), phone.did)
        assertEquals(CommandResult(Outcome.DONE, say = "I opened the phone app with Flower Shop, work. Tap Call yourself."), result)
    }

    @Test
    fun aContactWithoutANumberIsFoundOnlyWhenNoOtherContactMatches() = runTest {
        val petra = Contact(6, listOf("Petra Ilić"), emptyList(), starred = false)
        val anaP = Contact(7, listOf("Ana P."), emptyList(), starred = false)
        val owner = Owner()

        calls(anaJ, anaP).call(CallTarget.Name("Ana", null), owner)

        assertEquals(emptyList<String>(), owner.asked)
        assertEquals(listOf("Calling Ana Jovanović, mobile."), owner.said)
        assertEquals(
            CommandResult(Outcome.FAILED, say = "Petra Ilić has no phone number."),
            calls(anaJ, petra).call(CallTarget.Name("Petra", null), Owner()),
        )
    }

    @Test
    fun aStrangerTooManyNamesAndAMissingKindAreSaid() = runTest {
        val many = (1L..5L).map { Contact(it, listOf("Ana Number$it"), listOf(mobile), starred = false) }

        assertEquals(
            CommandResult(Outcome.FAILED, say = "I can't find Nikola in your contacts."),
            calls(ana).call(CallTarget.Name("Nikola", null), Owner()),
        )
        assertEquals(
            CommandResult(Outcome.FAILED, say = "There are 5 contacts like Ana. Say the full name."),
            calls(*many.toTypedArray()).call(CallTarget.Name("Ana", null), Owner()),
        )
        assertEquals(
            CommandResult(Outcome.FAILED, say = "Ana Petrović has no home number."),
            calls(ana).call(CallTarget.Name("Ana Petrović", "home"), Owner()),
        )
    }

    private val screen = IntRect(0, 0, 1080, 2400)
    private val noEmergency = { _: String -> false }

    private fun element(
        key: String,
        window: Int,
        bounds: IntRect,
        packageName: String? = DIALER,
        text: String? = null,
        description: String? = null,
        viewId: String? = null,
        clickable: Boolean = true,
        parent: Int? = null,
    ) = ScreenElement(
        key = key, windowId = window, parent = parent, packageName = packageName, text = text, contentDescription = description,
        viewId = viewId?.let { "$packageName:id/$it" }, label = text ?: description, clickable = clickable, bounds = bounds,
    )

    // The phone app: its number field, which shows [field], a digit key, the delete key, the call button, a caption drawn
    // over the button outside its view tree, and a row that expands; [phoneApp] is its package, and [editable] whether its
    // field is a text field (Android's emergency dialer's is not).
    private fun dialer(field: String?, phoneApp: String = DIALER, editable: Boolean = true): ScreenSnapshot {
        val app = WindowSummary(1, WindowKind.APPLICATION, packageName = phoneApp, bounds = screen)
        return ScreenSnapshot(
            0,
            listOf(app),
            listOf(
                ScreenElement(
                    key = "digits", windowId = 1, packageName = phoneApp, viewId = "$phoneApp:id/digits", text = field,
                    editable = editable, bounds = IntRect(0, 0, 1080, 200),
                ),
                element("five", 1, IntRect(400, 1000, 680, 1200), phoneApp, text = "5", description = "5,JKL", viewId = "five"),
                element("delete", 1, IntRect(800, 2000, 1000, 2200), phoneApp, description = "backspace", viewId = "deleteButton"),
                element("call", 1, IntRect(400, 2000, 680, 2200), phoneApp, description = "dial", viewId = "dialpad_voice_call_button"),
                element("caption", 1, IntRect(450, 2050, 630, 2150), phoneApp, text = "Call", clickable = false),
                element("more", 1, IntRect(0, 300, 1080, 400), phoneApp, text = "More").copy(actions = setOf(ElementAction.EXPAND)),
            ),
        )
    }

    // Numbers on the screen: 1 on the row, 2 on the call button, 3 on the digit key, 4 on the delete key and 5 on the field.
    private fun view(snapshot: ScreenSnapshot) = ScreenView(
        snapshot,
        listOf("more", "call", "five", "delete", "digits").mapIndexed { index, key -> Badge(index + 1, key, snapshot.element(key)!!.bounds) },
        grid = null,
    )

    private fun needsYes(snapshot: ScreenSnapshot, command: Command, emergency: (String) -> Boolean? = noEmergency) =
        EmergencyGuard.needsYes(EmergencyGuard.reaches(command, screen), view(snapshot), EmergencyGuard.PHONE_PACKAGES, emergency)

    @Test
    fun aPhoneWindowWhoseNumberKestrelWouldNotCallNeedsTheYesForAllButItsKeysFieldAndDelete() {
        val tapCall = Command.Tap(Target.Number(2))
        // A number that Kestrel would call, and an empty field, need nothing.
        assertFalse(needsYes(dialer("064 123 4567"), tapCall))
        assertFalse(needsYes(dialer(null), tapCall))
        // An emergency number, a number that Android cannot check, a short number, a code, and a field that reads as no number.
        assertTrue(needsYes(dialer("112"), tapCall) { it == "112" })
        assertTrue(needsYes(dialer("064 123 4567"), tapCall) { null })
        for (field in listOf("1985", "*#06#", "abc", "112 Call")) {
            assertTrue(field, needsYes(dialer(field), tapCall))
        }
        // The whole window, not one button: the row, the caption over the button, a bare "call" or "dial".
        for (command in listOf(Command.Tap(Target.Number(1)), Command.Tap(Target.Label("call")), Command.Bare.of("dial"))) {
            assertTrue(command.toString(), needsYes(dialer("112"), command))
        }
        // The exceptions, so that the owner can type and correct the number by voice.
        val keys = listOf(Command.Tap(Target.Number(3)), Command.Tap(Target.Number(4)), Command.LongPress(Target.Number(4)), Command.Tap(Target.Number(5)))
        for (command in keys) {
            assertFalse(command.toString(), needsYes(dialer("112"), command))
        }
        // Only a tap on a digit key: a long press on 1 calls the voicemail, and one on 0 types "+".
        assertTrue(needsYes(dialer("112"), Command.LongPress(Target.Number(3))) { it == "112" })
        assertTrue(needsYes(dialer("112"), Command.LongPress(Target.Label("5"))) { it == "112" })
        // The same window in an app that is no phone app needs nothing.
        assertFalse(needsYes(dialer("112", phoneApp = "com.example.chat"), tapCall))
        // A button whose name is one digit and a word is no key of the dial pad.
        val sim = element("sim", 1, IntRect(0, 1800, 300, 1900), text = "1 Telenor")
        val withSim = dialer("112").let { it.copy(elements = it.elements + sim) }
        assertTrue(needsYes(withSim, Command.Tap(Target.Label("1 Telenor"))) { it == "112" })
        // A dialog of the phone app, such as "Call with a note", is a window of its own, which counts too.
        val note = WindowSummary(2, WindowKind.APPLICATION, packageName = DIALER, bounds = IntRect(100, 800, 980, 1400))
        val send = element("send", 2, IntRect(600, 1300, 900, 1380), text = "Send and call")
        val withDialog = dialer("112").let { it.copy(windows = listOf(note) + it.windows, elements = it.elements + send) }
        assertTrue(needsYes(withDialog, Command.Tap(Target.Label("send and call"))) { it == "112" })
        // A name in the phone app's search box asks nothing; letters in the field with the view ID digits do.
        val search = dialer(null).let { snapshot ->
            snapshot.copy(elements = snapshot.elements + ScreenElement(
                key = "search", windowId = 1, packageName = DIALER, viewId = "$DIALER:id/search_box", text = "Ana", editable = true,
                bounds = IntRect(0, 200, 1080, 300),
            ))
        }
        assertFalse(needsYes(search, tapCall))
    }

    @Test
    fun everyCommandThatActsInSuchAWindowNeedsTheYesAndOnlyThose() {
        val snapshot = dialer("1985")
        for (command in listOf(
            Command.Expand(Target.Number(1)), Command.Collapse(Target.Number(2)), Command.Check(Target.Number(2), on = true),
            Command.Scroll(Direction.DOWN, Target.Number(2)), Command.Scroll(Direction.DOWN, null), Command.LongPress(Target.Number(2)),
            Command.Swipe(Direction.UP, Target.Number(3)), Command.Drag(Target.Number(3), Target.Number(4)),
            Command.Drag(Target.Number(3), null), Command.Dismiss(Target.Number(1)), Command.Bare.of("2"),
        )) {
            assertTrue(command.toString(), needsYes(snapshot, command))
        }
        for (command in listOf(Command.WhatIs(2), Command.ShowNumbers, TextCommand.NewLine, Command.Bare.of("3"))) {
            assertFalse(command.toString(), needsYes(snapshot, command))
        }
    }

    @Test
    fun aPhoneAppsWindowWithoutANumberFieldToReadNeedsTheYesForEveryActionThatReachesIntoIt() {
        val app = WindowSummary(1, WindowKind.APPLICATION, packageName = DIALER, bounds = screen)
        // The phone app's list of calls: rows whose numbers hide behind names, and no number field.
        val calls = ScreenSnapshot(
            0,
            listOf(app),
            listOf(
                element("row", 1, IntRect(0, 300, 1080, 500), text = "Mom"),
                element("back", 1, IntRect(900, 300, 1080, 500), description = "Call back"),
                element("tab", 1, IntRect(0, 2200, 540, 2400), text = "Keypad"),
            ),
        )
        val grid = Grid(screen, columns = 2)
        fun yes(snapshot: ScreenSnapshot, command: Command, grid: Grid? = null) = EmergencyGuard.needsYes(
            EmergencyGuard.reaches(command, screen), ScreenView(snapshot, emptyList(), grid), EmergencyGuard.PHONE_PACKAGES, noEmergency,
        )

        assertEquals(setOf(1), EmergencyGuard.blindWindows(calls, EmergencyGuard.PHONE_PACKAGES))
        for (command in listOf(
            Command.Tap(Target.Label("Mom")), Command.LongPress(Target.Label("Mom")), Command.Tap(Target.Label("call back")),
            Command.Bare.of("keypad"), Command.Scroll(Direction.DOWN, null), Command.Swipe(Direction.UP, null),
        )) {
            assertTrue(command.toString(), yes(calls, command))
        }
        assertTrue(yes(calls, Command.Tap(Target.Number(3)), grid))
        // A window that is still starting shows no element yet: a point, a grid square or a gesture inside it.
        val starting = ScreenSnapshot(0, listOf(app), emptyList())
        for (command in listOf(Command.Scroll(Direction.DOWN, null), Command.SwipeFromEdge(Edge.BOTTOM), Command.Pinch(out = true, target = null))) {
            assertTrue(command.toString(), yes(starting, command))
        }
        assertTrue(yes(starting, Command.Tap(Target.Number(5)), grid))
        // A number that is shown as plain text, neither in a text field nor with the view ID digits, is none that Kestrel reads.
        val shown = element("number", 1, IntRect(0, 0, 1080, 200), text = "064 123 4567", viewId = "dialer_number", clickable = false)
        assertTrue(yes(calls.copy(elements = calls.elements + shown), Command.Tap(Target.Label("Mom"))))
        // A window with a number field that Kestrel reads, and another app's window, keep their rules.
        assertFalse(yes(dialer("064 123 4567"), Command.Tap(Target.Label("more"))))
        assertFalse(yes(dialer(null, phoneApp = "com.example.chat").copy(elements = emptyList()), Command.Scroll(Direction.DOWN, null)))
    }

    @Test
    fun anElementOfAPhoneAppThatShowsAnEmergencyNumberAsAWholeNeedsTheYesForItsWindow() {
        val tapCall = Command.Tap(Target.Number(2))
        // A call-history row "112" next to a dial field that shows an ordinary number: the row and the call button ask.
        val row = element("row", 1, IntRect(0, 500, 1080, 600), text = "112")
        val withRow = dialer("064 123 4567").let { it.copy(elements = it.elements + row) }
        assertTrue(needsYes(withRow, Command.Tap(Target.Label("112"))) { it == "112" })
        assertTrue(needsYes(withRow, tapCall) { it == "112" })
        // Also by its content description, and when Android cannot check the number.
        val described = dialer("064 123 4567").let { it.copy(elements = it.elements + row.copy(text = null, contentDescription = " 112 ")) }
        assertTrue(needsYes(described, tapCall) { it == "112" })
        assertTrue(needsYes(withRow, tapCall) { if (it == "112") null else false })
        // A window whose only text field is a search box, with a result "192" that Android counts as an emergency number.
        val app = WindowSummary(1, WindowKind.APPLICATION, packageName = DIALER, bounds = screen)
        val search = ScreenElement(
            key = "search", windowId = 1, packageName = DIALER, viewId = "$DIALER:id/search_box", text = null, editable = true,
            bounds = IntRect(0, 0, 1080, 200),
        )
        val results = ScreenSnapshot(0, listOf(app), listOf(search, element("result", 1, IntRect(0, 300, 1080, 400), text = "192")))
        assertTrue(
            EmergencyGuard.needsYes(
                EmergencyGuard.reaches(Command.Tap(Target.Label("192")), screen), ScreenView(results, emptyList(), grid = null),
                EmergencyGuard.PHONE_PACKAGES, emergency = { it == "192" },
            ),
        )
        // A time such as "08:15" is no number, also where Android counts 08 as an emergency number without a SIM card.
        val time = element("time", 1, IntRect(0, 500, 1080, 600), text = "08:15", clickable = false)
        val withTime = dialer("064 123 4567").let { it.copy(elements = it.elements + time) }
        assertFalse(needsYes(withTime, tapCall) { it.startsWith("08") })
    }

    @Test
    fun theGuardReadsTheScreenASecondTimeOnlyWhileAPhoneAppIsOnIt() = runTest {
        val phoneApps = EmergencyGuard.PHONE_PACKAGES
        val chat = ScreenSnapshot(
            0,
            listOf(WindowSummary(1, WindowKind.APPLICATION, packageName = "com.example.chat", bounds = screen)),
            listOf(element("row", 1, IntRect(0, 300, 1080, 500), packageName = "com.example.chat", text = "Mom")),
        )
        var reads = 0
        val tapMom = EmergencyGuard.reaches(Command.Tap(Target.Label("Mom")), screen)
        assertNull(EmergencyGuard.askedOn(tapMom, phoneApps, noEmergency) {
            reads++
            EmergencyGuard.fresh(ScreenView(chat, emptyList(), grid = null), phoneApps, emptyMap())
        })
        assertEquals(1, reads)
        // With the phone app on the screen, once more right before the guard lets the command go on.
        reads = 0
        val tapRow = EmergencyGuard.reaches(Command.Tap(Target.Number(1)), screen)
        assertNull(EmergencyGuard.askedOn(tapRow, phoneApps, noEmergency) {
            reads++
            EmergencyGuard.fresh(view(dialer("064 123 4567")), phoneApps, mapOf("digits" to "064 123 4567"))
        })
        assertEquals(2, reads)
    }

    @Test
    fun aSwipeOnAnElementCountsWhereItEndsAndADragWhereItsSecondElementIs() {
        // Split screen: a chat above, and the phone app with 112 below.
        val chat = "com.example.chat"
        val split = ScreenSnapshot(
            0,
            listOf(
                WindowSummary(3, WindowKind.APPLICATION, packageName = chat, bounds = IntRect(0, 0, 1080, 1200)),
                WindowSummary(1, WindowKind.APPLICATION, packageName = DIALER, bounds = IntRect(0, 1200, 1080, 2400)),
            ),
            listOf(
                element("message", 3, IntRect(0, 900, 1080, 1100), packageName = chat, text = "Hello"),
                ScreenElement(
                    key = "digits", windowId = 1, packageName = DIALER, viewId = "$DIALER:id/digits", text = "112", editable = true,
                    bounds = IntRect(0, 1200, 1080, 1400),
                ),
                element("call", 1, IntRect(400, 2000, 680, 2200), description = "dial", viewId = "dialpad_voice_call_button"),
            ),
        )
        fun yes(command: Command) = EmergencyGuard.needsYes(
            EmergencyGuard.reaches(command, screen), ScreenView(split, emptyList(), grid = null), EmergencyGuard.PHONE_PACKAGES,
            emergency = { it == "112" },
        )

        // Down from the message, a third of the screen, the swipe ends in the phone app; up, it stays in the chat.
        assertTrue(yes(Command.Swipe(Direction.DOWN, Target.Label("hello"))))
        assertFalse(yes(Command.Swipe(Direction.UP, Target.Label("hello"))))
        assertTrue(yes(Command.Drag(Target.Label("hello"), Target.Label("dial"))))
        assertFalse(yes(Command.Tap(Target.Label("hello"))))
    }

    @Test
    fun aCancelThatComesDuringTheReadsStopsTheClickOnCall() = runTest {
        val fields = AndroidDialer.numberFields(dialer("064 123 4567"), setOf(DIALER))
        var reads = 0
        var clicked = false
        val press = launch {
            AndroidDialer.pressChecked(fields, "0641234567", read = {
                // A "stop" that comes while the second read blocks in a binder call, which does not notice it.
                if (++reads == 2) currentCoroutineContext().job.cancel()
                mapOf("digits" to "064 123 4567")
            }) {
                clicked = true
                true
            }
        }
        press.join()

        assertEquals(2, reads)
        assertTrue(press.isCancelled)
        assertFalse(clicked)
    }

    @Test
    fun theGuardsRuleForSearchForAsksOnlyWhereTheRulesApplyThoughNoCommandReachesItToday() {
        // This pins the guard's own rule, not a path that runs today: the text part takes "search for" first, also in a phone
        // app, and refuses a number there itself (refusesSearch, which searchForANumberIsRefusedInAPhoneAppAndANameIsSearchedFor
        // checks), so the guard never sees it. The rule stays as a defence: "search for" taps a search button and types into a
        // search field, which in a phone package's window may be its number field, so it may act anywhere there.
        assertTrue(needsYes(dialer("1985"), TextCommand.Search("112")))
        assertTrue(needsYes(dialer("112"), TextCommand.Search("Ana")) { it == "112" })
        // Where neither rule applies, it needs nothing.
        assertFalse(needsYes(dialer("064 123 4567"), TextCommand.Search("Ana")))
        assertFalse(needsYes(dialer("1985", phoneApp = "com.example.chat"), TextCommand.Search("Ana")))
    }

    @Test
    fun androidsEmergencyDialerCountsAsAPhoneWindowAlthoughItsFieldIsNoTextField() {
        val emergencyDialer = dialer("112", phoneApp = "com.android.phone", editable = false)

        assertTrue(needsYes(emergencyDialer, Command.Tap(Target.Label("dial"))) { it == "112" })
        assertFalse(needsYes(emergencyDialer, Command.Tap(Target.Number(3))) { it == "112" })
        assertEquals(listOf("112"), AndroidDialer.fieldTexts(emergencyDialer, "com.android.phone"))

        // One UI's emergency dialer, whose number field Kestrel cannot read, counts by its title.
        val samsung = "com.samsung.android.app.telephonyui"
        val oneUi = ScreenSnapshot(
            0,
            listOf(WindowSummary(1, WindowKind.APPLICATION, packageName = samsung, title = "Emergency call", bounds = screen)),
            listOf(
                element("number", 1, IntRect(0, 0, 1080, 200), samsung, text = "112", viewId = "dialer_number", clickable = false),
                element("one", 1, IntRect(0, 1000, 360, 1200), samsung, text = "1", viewId = "one"),
                element("call", 1, IntRect(400, 2000, 680, 2200), samsung, text = "Call"),
            ),
        )
        val view = ScreenView(oneUi, emptyList(), grid = null)
        fun yes(command: Command) =
            EmergencyGuard.needsYes(EmergencyGuard.reaches(command, screen), view, EmergencyGuard.PHONE_PACKAGES, noEmergency)
        assertTrue(yes(Command.Tap(Target.Label("call"))))
        // Its number field is no text field and has not the view ID digits, so Kestrel cannot read the number that a digit
        // key would add to, and the keys need the yes too.
        assertTrue(yes(Command.Tap(Target.Label("1"))))

        // Without a title, a text that R1 names guards the package's other elements, and its windows without elements too.
        val popup = WindowSummary(2, WindowKind.APPLICATION, packageName = samsung, bounds = IntRect(0, 1500, 1080, 1900))
        val untitled = ScreenSnapshot(
            0,
            listOf(popup, WindowSummary(1, WindowKind.APPLICATION, packageName = samsung, bounds = screen)),
            oneUi.elements + element("head", 1, IntRect(0, 300, 1080, 400), samsung, text = "Emergency call", clickable = false),
        )
        val untitledView = ScreenView(untitled, emptyList(), grid = null)
        val tapCall = EmergencyGuard.reaches(Command.Tap(Target.Label("call")), screen)
        assertTrue(EmergencyGuard.needsYes(tapCall, untitledView, EmergencyGuard.PHONE_PACKAGES, noEmergency))
        assertEquals(setOf(1, 2), EmergencyGuard.guardedWindows(untitled, EmergencyGuard.PHONE_PACKAGES, noEmergency))
    }

    @Test
    fun theGuardDecidesOnTheNumberFieldAsThePhoneAppReportsItNowAndAsksWhenItCannotReadIt() = runTest {
        val tapCall = EmergencyGuard.reaches(Command.Tap(Target.Number(2)), screen)
        val phoneApps = EmergencyGuard.PHONE_PACKAGES
        val emergency = { digits: String -> digits == "112" }
        // The snapshot still shows a number that Kestrel would call, while the phone app shows 112 by now.
        val stale = view(dialer("064 123 4567"))
        assertFalse(EmergencyGuard.needsYes(tapCall, stale, phoneApps, emergency))
        fun reads(vararg texts: Map<String, String?>): suspend () -> EmergencyGuard.FreshView? {
            val queue = ArrayDeque(texts.toList())
            return { EmergencyGuard.fresh(stale, phoneApps, queue.removeFirst()) }
        }

        assertNotNull(EmergencyGuard.askedOn(tapCall, phoneApps, emergency, reads(mapOf("digits" to "112"))))
        // A field that Kestrel cannot read again asks, as one that shows no number does.
        assertNotNull(EmergencyGuard.askedOn(tapCall, phoneApps, emergency, reads(emptyMap())))
        // As the snapshot says, read right before the decision and again right before the command acts: no question.
        assertNull(EmergencyGuard.askedOn(tapCall, phoneApps, emergency, reads(mapOf("digits" to "064 123 4567"), mapOf("digits" to "064 123 4567"))))
        // The phone app shows 112 only by the second read.
        assertNotNull(EmergencyGuard.askedOn(tapCall, phoneApps, emergency, reads(mapOf("digits" to "064 123 4567"), mapOf("digits" to "112"))))
        // A name that the search box shows now asks nothing; the keys stay free.
        val withSearch = dialer("064 123 4567").let { snapshot ->
            snapshot.copy(elements = snapshot.elements + ScreenElement(
                key = "search", windowId = 1, packageName = DIALER, viewId = "$DIALER:id/search_box", editable = true,
                bounds = IntRect(0, 200, 1080, 300),
            ))
        }
        val named = EmergencyGuard.fresh(view(withSearch), phoneApps, mapOf("digits" to "064 123 4567", "search" to "Ana"))
        assertFalse(EmergencyGuard.needsYes(tapCall, named.view, phoneApps, emergency, named.unreadable))
        val unread = EmergencyGuard.fresh(stale, phoneApps, emptyMap())
        assertFalse(EmergencyGuard.needsYes(EmergencyGuard.reaches(Command.Tap(Target.Number(3)), screen), unread.view, phoneApps, emergency, unread.unreadable))
    }

    @Test
    fun callIsPressedOnlyWhileTheNumberFieldShowsExactlyTheAnnouncedNumberRightBeforeTheClick() = runTest {
        val fields = AndroidDialer.numberFields(dialer("064 123 4567"), setOf(DIALER))
        var clicks = 0
        suspend fun press(digits: String, vararg texts: Map<String, String?>, clicked: Boolean = true): Pressed? {
            val queue = ArrayDeque(texts.toList())
            return AndroidDialer.pressChecked(fields, digits, read = { queue.removeFirst() }) {
                clicks++
                clicked
            }
        }
        fun shows(text: String?) = mapOf("digits" to text)

        assertNull(press("0641234567", shows("064 123 4567"), shows("064-123-4567")))
        assertEquals(1, clicks)
        // Another number by the read right before the click, a field that Kestrel cannot read, and an empty field.
        assertEquals(Pressed.OTHER_NUMBER, press("0641234567", shows("064 123 4567"), shows("064 123 4568")))
        assertEquals(Pressed.REFUSED, press("0641234567", emptyMap()))
        assertEquals(Pressed.REFUSED, press("0641234567", shows(null)))
        // Exactly the announced number: its "+", "*", "#", pause and wait count, not only its digits.
        for (text in listOf("*064 123 4567#", "064 123 4567,112", "+064 123 4567", "abc")) {
            assertEquals(text.length.toString(), Pressed.OTHER_NUMBER, press("0641234567", shows(text)))
        }
        assertEquals(Pressed.OTHER_NUMBER, press("+381641234567", shows("381 64 123 4567")))
        assertNull(press("+381641234567", shows("+381 64 123 4567"), shows("+381 64 123 4567")))
        assertEquals(2, clicks)
        // A click that the button refuses.
        assertEquals(Pressed.REFUSED, press("0641234567", shows("064 123 4567"), shows("064 123 4567"), clicked = false))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theCallButtonIsAwaitedFromWhenThePhoneAppShowsAsAfterAColdStart() = runTest {
        val nothing = ScreenSnapshot(0, emptyList(), emptyList())
        val withButton = dialer("064 123 4567")
        val withoutButton = withButton.copy(elements = withButton.elements.filter { it.key == "digits" })
        // A cold start: nothing of the phone app for 4 s, then its window without the button for 1 s.
        var start = testScheduler.currentTime
        val found = AndroidDialer.awaitCallButton(DIALER) {
            when (testScheduler.currentTime - start) {
                in 0 until 4_000 -> nothing
                in 4_000 until 5_000 -> withoutButton
                else -> withButton
            }
        }
        assertEquals("call", found?.key)
        // Without a button, Kestrel gives up 3 s after the phone app showed, and after 10 s when it never shows.
        start = testScheduler.currentTime
        assertNull(AndroidDialer.awaitCallButton(DIALER) { withoutButton })
        assertTrue(testScheduler.currentTime - start in 3_000..3_200)
        start = testScheduler.currentTime
        assertNull(AndroidDialer.awaitCallButton(DIALER) { nothing })
        assertTrue(testScheduler.currentTime - start in 10_000..10_200)
    }

    @Test
    fun searchForANumberIsRefusedInAPhoneAppAndANameIsSearchedFor() {
        val phoneApps = EmergencyGuard.PHONE_PACKAGES
        val phone = dialer(null).let { it.copy(windows = it.windows.map { window -> window.copy(active = true) }) }
        val chat = dialer(null, phoneApp = "com.example.chat").let { it.copy(windows = it.windows.map { window -> window.copy(active = true) }) }

        for (query in listOf("112", "Ana 1", "\u0661\u0661\u0662")) {
            assertTrue(query.length.toString(), EmergencyGuard.refusesSearch(query, phone, null, phoneApps))
        }
        assertFalse(EmergencyGuard.refusesSearch("Ana", phone, null, phoneApps))
        assertFalse(EmergencyGuard.refusesSearch("112", chat, null, phoneApps))
        // A search button or field of a phone app counts also when another app is in front.
        assertTrue(EmergencyGuard.refusesSearch("112", chat, phone.elements.first(), phoneApps))
    }

    @Test
    fun aLateYesCountsOnlyWhileTheCommandFindsTheSameElementsInTheSameWindows() {
        val snapshot = dialer("112")
        val reaches = EmergencyGuard.reaches(Command.Tap(Target.Number(2)), screen)
        val asked = EmergencyGuard.resolved(reaches, view(snapshot))

        assertEquals(asked, EmergencyGuard.resolved(reaches, view(snapshot)))
        // The numbers changed under the question: 2 now names the row.
        val moved = ScreenView(snapshot, listOf(Badge(2, "more", snapshot.element("more")!!.bounds)), grid = null)
        assertFalse(asked == EmergencyGuard.resolved(reaches, moved))
        // The same key in another window.
        val other = snapshot.copy(elements = snapshot.elements.map { if (it.key == "call") it.copy(windowId = 2) else it })
        assertFalse(asked == EmergencyGuard.resolved(reaches, view(other)))
    }

    @Test
    fun anElementNamedEmergencyOrSosNeedsTheYesAlsoThroughItsParentAndACaptionOverIt() {
        val ui = "com.android.systemui"
        val menu = WindowSummary(1, WindowKind.SYSTEM, packageName = ui, bounds = screen)
        val snapshot = ScreenSnapshot(
            0,
            listOf(menu),
            listOf(
                element("power", 1, IntRect(0, 0, 360, 300), ui, text = "Power off"),
                element("sos", 1, IntRect(360, 0, 720, 300), ui, viewId = "emergencyButton"),
                // A button inside the emergency button, which takes a touch of its own.
                element("icon", 1, IntRect(400, 50, 680, 250), ui, description = "Phone icon", parent = 1),
                element("help", 1, IntRect(720, 0, 1080, 300), ui, description = "SOS messages"),
                element("caption", 1, IntRect(450, 100, 630, 200), ui, text = "Call", clickable = false),
            ),
        )
        val view = ScreenView(snapshot, emptyList(), grid = null)
        fun yes(command: Command) =
            EmergencyGuard.needsYes(EmergencyGuard.reaches(command, screen), view, EmergencyGuard.PHONE_PACKAGES, noEmergency)

        assertTrue(EmergencyGuard.emergencyNamed(snapshot, 1))
        for (label in listOf("phone icon", "SOS messages", "call")) {
            assertTrue(label, yes(Command.Tap(Target.Label(label))))
        }
        assertFalse(yes(Command.Tap(Target.Label("power off"))))
        // Kestrel's own bar, which shows the question, is no emergency button.
        val bar = snapshot.copy(elements = listOf(element("bar", 1, screen, "dev.kestrel", text = PhoneCommandTexts.EMERGENCY_QUESTION)))
        assertFalse(EmergencyGuard.emergencyNamed(bar, 0))
    }

    @Test
    fun thePhoneNumbersOnTheScreenComeFromTheAppWindowsOnly() {
        val app = WindowSummary(1, WindowKind.APPLICATION, bounds = IntRect(0, 0, 1080, 2400))
        val bar = WindowSummary(2, WindowKind.SYSTEM, bounds = IntRect(0, 0, 1080, 100))
        fun text(key: String, text: String, window: Int = 1, editable: Boolean = false) =
            ScreenElement(key = key, windowId = window, text = text, bounds = IntRect(0, 0, 10, 10), editable = editable)
        val snapshot = ScreenSnapshot(
            0,
            listOf(bar, app),
            listOf(
                text("office", "Office: +381 11 234 5678"),
                text("again", "Office +381-11-234-5678"),
                text("date", "Meeting on 17.11.2026"),
                text("status", "Missed call from 064 999 8888", window = 2),
                text("field", "060 111 2222", editable = true),
                text("mobile", "Mobile 064 123 4567"),
            ),
        )

        val found = NumberCopier.numbersOn(snapshot).map { it.first.key to it.second }

        assertEquals(listOf("office" to "+381 11 234 5678", "mobile" to "064 123 4567"), found)
    }
}
