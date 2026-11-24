package dev.kestrel.safety

import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScrollAxis
import dev.kestrel.screen.model.WindowKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 16.2's decision table (design D13 of sub-project 5), with a hostile model that declares `reading` for every tap. */
class SafetyCheckTest {
    private val everything = TaskSafety(TaskAllowance(setOf(Category.SENDING, Category.CALLS, Category.DELETING)))
    private val nothing = TaskSafety(TaskAllowance.NONE)
    private val standard = SafetySettings(testMode = false)

    private fun tap(key: String, declared: Category = Category.READING) = Proposal("tap", ActionKind.TAP, declared, targetKey = key, frontAppLabel = "Chat")

    private fun kind(decision: Decision): String = when (decision) {
        Decision.Run -> "run"
        is Decision.Confirm -> "confirm"
        is Decision.HandOver -> "handover:${decision.handOver.kind}"
        is Decision.Block -> "block"
    }

    private fun decide(proposal: Proposal, screen: TestScreen, task: TaskSafety = everything, mode: Mode = Mode.STANDARD, env: SafetyEnvironment = SafetyEnvironment()) =
        kind(SafetyCheck.decide(proposal, screen.snapshot(), task, standard.copy(mode = mode), env))

    /**
     * A chat whose toolbar names it and whose message field holds text, so that a question can name what a delete
     * removes and where a send goes.
     */
    private fun titled(): TestScreen = TestScreen().apply {
        val bar = add(80, null, id = "toolbar", clickable = false)
        add(90, "Shopping list", parent = bar, clickable = false)
        add(1800, null, editable = true, hint = "Message", text = "Hello", right = 850)
    }

    @Test
    fun everyRowRunsConfirmsHandsOverOrBlocksAsItsModeSays() {
        val screen = titled()
        val next = screen.add(1000, "Next")
        val expected = mapOf(
            Category.READING to listOf("run", "run", "run"),
            Category.TYPING_REQUEST to listOf("run", "run", "run"),
            Category.ORDINARY_SETTINGS to listOf("run", "run", "run"),
            Category.TYPING_SCREEN_TEXT to listOf("confirm", "run", "run"),
            Category.SENDING to listOf("confirm", "confirm", "run"),
            Category.CALLS to listOf("handover:CALL", "handover:CALL", "handover:CALL"),
            Category.DELETING to listOf("confirm", "confirm", "run"),
            Category.PAYMENTS to listOf("handover:PAYMENT", "handover:PAYMENT", "handover:PAYMENT"),
            Category.CREDENTIALS to listOf("handover:CREDENTIALS", "handover:CREDENTIALS", "handover:CREDENTIALS"),
            Category.PROTECTED to listOf("block", "block", "block"),
        )
        for ((category, decisions) in expected) {
            assertEquals(category.name, decisions, Mode.entries.map { decide(tap(next, category), screen, mode = it) })
        }
    }

    @Test
    fun aHostileModelThatDeclaresReadingStillMeetsTheLabelsWords() {
        val screen = titled()
        val keys = listOf("Send", "Delete chat", "Pay now", "Install", "Pošalji", "Пошаљи", "Video call").map { screen.add(100 + it.length * 50, it) }

        assertEquals(
            listOf("confirm", "confirm", "handover:PAYMENT", "block", "confirm", "confirm", "handover:CALL"),
            keys.map { decide(tap(it), screen) },
        )
        val assessment = SafetyCheck.assess(tap(keys[0]), screen.snapshot(), everything, standard)
        assertEquals(Category.SENDING, assessment.category)
        assertEquals(setOf(Source.DECLARED, Source.LABEL_WORDS), assessment.sources)
        assertEquals(assessment.decision, assessment.beforeTestMode)
        val deleted = SafetyCheck.decide(tap(keys[1]), screen.snapshot(), everything, standard) as Decision.Confirm
        assertEquals("Delete 'Shopping list' in Chat? Say confirm or cancel.", deleted.confirmation.question)
        // Without a title or a selection, Kestrel cannot say what a delete removes, so it hands over.
        val untitled = TestScreen()
        assertEquals("handover:RECIPIENT", decide(tap(untitled.add(500, "Delete")), untitled))
    }

    @Test
    fun clearBesideAFieldOnlyEmptiesItAndClearChatDeletes() {
        val screen = TestScreen()
        val field = screen.add(100, null, editable = true, hint = "Search", text = "Ana")
        val clear = screen.add(100, "Clear", parent = field, left = 900, className = "android.widget.ImageButton")
        val clearSearch = screen.add(100, "Clear search", left = 800, right = 900)
        val clearChat = screen.add(1500, "Clear chat")

        assertEquals(Category.TYPING_REQUEST, SafetyCheck.assess(tap(clear), screen.snapshot(), nothing, standard).category)
        assertEquals("run", decide(tap(clearSearch), screen, nothing))
        assertEquals(Category.DELETING, SafetyCheck.assess(tap(clearChat), screen.snapshot(), everything, standard).category)
    }

    @Test
    fun anUnlabelledButtonBesideAComposedMessageSends() {
        val chat = TestScreen(title = "Ana")
        chat.add(1800, null, editable = true, focused = true, hint = "Message", text = "I'm late", right = 850)
        val arrow = chat.add(1800, null, className = "android.widget.ImageButton", left = 850)
        val search = TestScreen()
        search.add(100, null, editable = true, focused = true, hint = "Search", text = "Ana", right = 850)
        val lens = search.add(100, null, className = "android.widget.ImageButton", left = 850)
        val browser = TestScreen("com.android.chrome")
        browser.add(100, null, id = "url_bar", editable = true, focused = true, text = "example.com", right = 850)
        val menu = browser.add(100, null, className = "android.widget.ImageButton", left = 850)

        assertEquals("confirm", decide(tap(arrow), chat, nothing))
        assertEquals(Category.SENDING, SafetyCheck.assess(tap(arrow), chat.snapshot(), everything, standard).category)
        assertEquals("run", decide(tap(lens), search, nothing))
        assertEquals("run", decide(tap(menu), browser, nothing))
    }

    @Test
    fun everyActionInAPhoneOrContactsAppIsACallThatIsHandedOver() {
        val dialer = TestScreen("com.google.android.dialer")
        val one = dialer.add(1200, "1")
        val list = dialer.add(200, null, scrollable = true, height = 800, clickable = false)
        val contacts = TestScreen("com.google.android.contacts")
        val row = contacts.add(300, "Ana Petrović")
        val other = TestScreen("com.example.phone")
        val key = other.add(1200, "5")

        assertEquals("handover:CALL", decide(tap(one), dialer, nothing))
        assertEquals("run", decide(Proposal("scroll", ActionKind.SCROLL, Category.READING, targetKey = list), dialer, nothing))
        assertEquals("handover:CALL", decide(tap(row), contacts, nothing))
        assertEquals("run", decide(tap(key), other, nothing))
        val withDefault = SafetyEnvironment(phonePackages = EmergencyGuard.PHONE_PACKAGES + "com.example.phone")
        assertEquals("handover:CALL", decide(tap(key), other, nothing, Mode.FULL_AUTO, withDefault))
    }

    @Test
    fun anEmergencyButtonAnywhereIsACall() {
        val lock = TestScreen("com.android.systemui")
        val sos = lock.add(1800, "Emergency call")

        assertEquals("handover:CALL", decide(tap(sos), lock, everything, Mode.FULL_AUTO))
    }

    @Test
    fun anOcrLineThatNamesAnEmergencyUnderATapAtIsACall() {
        val game = TestScreen("com.example.game")
        game.add(0, null, className = "android.view.SurfaceView", height = 2000, clickable = false)

        for (text in listOf("SOS", "Emergency")) {
            val at = Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(500, 1500), pointText = text, frontAppLabel = "Game")
            assertEquals(text, "handover:CALL", decide(at, game, nothing, Mode.FULL_AUTO))
            assertEquals(text, "block", kind(SafetyCheck.decide(at, game.snapshot(), nothing, SafetySettings(mode = Mode.FULL_AUTO))))
        }
    }

    @Test
    fun gmailsForwardStartsNoShareFlowSoLaterTapsAreJudgedByTheirOwnRules() {
        val message = TestScreen(SelfChats.GMAIL)
        message.add(100, "Plan", clickable = false)
        val forward = message.add(1800, "Forward")
        val compose = TestScreen(SelfChats.GMAIL)
        compose.add(80, "Send", left = 850)
        compose.add(200, null, id = "to", editable = true, hint = "To", text = "")
        compose.add(500, null, id = "body", editable = true, text = "")
        val inbox = TestScreen(SelfChats.GMAIL)
        val list = inbox.add(300, null, scrollable = true, axis = ScrollAxis.VERTICAL, height = 1500, clickable = false)
        val row = inbox.add(300, "Ana Lunch tomorrow", parent = list)

        val forwarded = SafetyCheck.afterAction(tap(forward), message.snapshot(), compose.snapshot(), everything)

        assertNull(forwarded.shareFlow)
        assertEquals("run", decide(tap(row), inbox, forwarded))
        // A flow that reaches an e-mail's compose screen ends there.
        val shared = everything.copy(shareFlow = ShareFlow(SelfChats.GMAIL, emptyList(), ShareCount(1, 0), serial = 1), flows = 1)
        val body = compose.add(600, null, editable = true, hint = "Compose email", text = "")
        assertNull(SafetyCheck.afterAction(Proposal("type_text", ActionKind.TYPE, targetKey = body, text = "Hi"), compose.snapshot(), compose.snapshot(), shared).shareFlow)
    }

    @Test
    fun everyRowOfASharePickerSendsWhateverItsLabelAndItsControlsOnlyRead() {
        val picker = TestScreen("org.telegram.messenger")
        val bar = picker.add(0, null, id = "toolbar", clickable = false, height = 150)
        val search = picker.add(20, "Search", parent = bar, left = 850, className = "android.widget.ImageButton")
        val tab = picker.add(160, "Chats", className = "com.google.android.material.tabs.TabLayout\$TabView", right = 300)
        val list = picker.add(300, null, scrollable = true, axis = ScrollAxis.VERTICAL, height = 1600, clickable = false)
        val rows = listOf("Close friends", "Back to school", "Search party", "Ana").mapIndexed { i, name -> picker.add(300 + i * 120, name, parent = list) }
        val flow = everything.copy(shareFlow = ShareFlow("org.telegram.messenger", emptyList(), ShareCount(3, 0)))

        assertEquals("run", decide(tap(search), picker, flow))
        assertEquals("run", decide(tap(tab), picker, flow))
        for (row in rows) {
            assertEquals(Category.SENDING, SafetyCheck.assess(tap(row), picker.snapshot(), flow, standard).category)
            assertEquals("confirm", decide(tap(row), picker, flow, Mode.RELAXED))
        }
        // Without a flow, a row named "Ana" only opens her chat.
        assertEquals("run", decide(tap(rows[3]), picker, everything))
    }

    @Test
    fun shareMediaAndComposeSmsNeedTheAllowanceToSend() {
        val screen = TestScreen()
        val share = Proposal(
            "share_media", ActionKind.SHARE_MEDIA, receivingPackage = "org.telegram.messenger", shareCount = ShareCount(3, 0),
            frontAppLabel = "Gallery", receivingAppLabel = "Telegram",
        )
        val sms = Proposal("compose_sms", ActionKind.COMPOSE_SMS, receivingPackage = "com.google.android.apps.messaging")

        val asked = SafetyCheck.decide(share, screen.snapshot(), nothing, standard) as Decision.Confirm
        assertEquals("You didn't ask me to share anything. Open Telegram's share screen with 3 photos? Say confirm or cancel.", asked.confirmation.question)
        assertEquals("confirm", decide(sms, screen, nothing))
        assertEquals("run", decide(share, screen, everything))
    }

    @Test
    fun protectedScreensBlockAndAnAppsPermissionDialogIsHandedOver() {
        val controller = TestScreen("com.google.android.permissioncontroller")
        val allow = controller.add(1500, "Allow")
        val dialog = TestScreen()
        dialog.window(2, "com.google.android.permissioncontroller", IntRect(50, 700, 950, 1300), active = true)
        val dialogAllow = dialog.add(1200, "While using the app", window = 2)
        val settings = TestScreen("com.android.settings")
        val bar = settings.add(80, null, id = "action_bar", clickable = false)
        settings.add(90, "Accessibility", parent = bar, clickable = false)
        val service = settings.add(400, "Kestrel")
        val list = settings.add(300, null, scrollable = true, height = 1500, clickable = false)
        val page = TestScreen("com.android.settings")
        val toggle = page.add(400, "Use Kestrel")

        assertEquals("block", decide(tap(allow), controller, everything, Mode.FULL_AUTO))
        assertEquals("handover:PERMISSION_DIALOG", decide(tap(dialogAllow), dialog))
        assertEquals("block", decide(tap(service), settings))
        assertEquals("run", decide(Proposal("scroll", ActionKind.SCROLL, Category.READING, targetKey = list), settings))
        assertEquals("block", decide(tap(toggle), page, everything.copy(trail = ProtectedTrail("Accessibility"))))
        assertEquals("run", decide(tap(toggle), page))
    }

    @Test
    fun setSliderIsAnElementActionWhoseCategoryTheModelDeclaresUnderTheContextRules() {
        fun slide(key: String, declared: Category = Category.ORDINARY_SETTINGS) =
            Proposal("set_slider", ActionKind.SET_SLIDER, declared, targetKey = key, percent = 30, frontAppLabel = "Settings")
        val settings = TestScreen("com.android.settings")
        val brightness = settings.add(400, "Display brightness", className = "android.widget.SeekBar")
        val protectedPage = TestScreen("com.android.settings")
        val bar = protectedPage.add(80, null, id = "action_bar", clickable = false)
        protectedPage.add(90, "Accessibility", parent = bar, clickable = false)
        val size = protectedPage.add(400, "Font size", className = "android.widget.SeekBar")
        val dialer = TestScreen("com.google.android.dialer")
        val volume = dialer.add(400, "Volume", className = "android.widget.SeekBar")
        val shop = TestScreen(title = "Shop")
        val shopBar = shop.add(80, null, id = "toolbar", clickable = false)
        shop.add(90, "Checkout", parent = shopBar, clickable = false)
        val tip = shop.add(1500, "Tip", className = "android.widget.SeekBar", clickable = false)

        assertEquals(listOf("run", "run", "run"), Mode.entries.map { decide(slide(brightness), settings, nothing, it) })
        assertEquals("block", decide(slide(size), protectedPage))
        assertEquals("handover:CALL", decide(slide(volume), dialer))
        assertEquals("handover:PAYMENT", decide(slide(tip), shop, everything, Mode.FULL_AUTO))
        assertEquals("confirm", decide(slide(brightness, Category.DELETING), settings))
        assertEquals(Decision.Run, SafetyCheck.decide(slide(brightness), settings.snapshot(), nothing, standard.copy(testMode = true)))
    }

    @Test
    fun theContactToolsAreTypingAndDeletingTestModeLetsThemThroughAndAContactsAppStaysACall() {
        val screen = TestScreen()
        val task = TaskSafety.start("add a contact maple heron with the number +1 202 555 0160")
        val deleteTask = TaskSafety.start("delete the contact Ana Test")
        fun add(name: String, number: String) = Proposal("add_contact", ActionKind.ADD_CONTACT, contactName = name, contactNumber = number, frontAppLabel = "Chat")
        val delete = Proposal("delete_contact", ActionKind.DELETE_CONTACT, contactName = "Ana Test", contactNumber = "+1 202 555 0143", frontAppLabel = "Chat")

        assertEquals("run", decide(add("Maple Heron", "+12025550160"), screen, task))
        assertEquals("confirm", decide(add("Maple Heron", "+1 202 555 0199"), screen, task))
        assertEquals("confirm", decide(add("Marko", "+12025550160"), screen, task))
        assertEquals(listOf("confirm", "confirm", "run"), Mode.entries.map { decide(delete, screen, deleteTask, it) })
        val asked = SafetyCheck.decide(delete, screen.snapshot(), deleteTask, standard) as Decision.Confirm
        assertEquals("Delete the contact 'Ana Test' (+1 202 555 0143)? Say confirm or cancel.", asked.confirmation.question)
        assertEquals(Category.DELETING, asked.confirmation.category)
        val outside = SafetyCheck.decide(delete, screen.snapshot(), task, standard.copy(mode = Mode.FULL_AUTO)) as Decision.Confirm
        assertEquals("You didn't ask me to delete anything. Delete the contact 'Ana Test' (+1 202 555 0143)? Say confirm or cancel.", outside.confirmation.question)
        val testMode = standard.copy(testMode = true, mode = Mode.FULL_AUTO)
        assertEquals(Decision.Run, SafetyCheck.decide(add("Maple Heron", "+12025550160"), screen.snapshot(), task, testMode))
        assertEquals(Decision.Run, SafetyCheck.decide(delete, screen.snapshot(), deleteTask, testMode))
        // The contacts apps stay guarded: every acting step there is a call, which is handed over.
        val contacts = TestScreen("com.google.android.contacts")
        assertEquals("handover:CALL", decide(tap(contacts.add(1800, "Save")), contacts))
        assertEquals(listOf("12025550160"), task.ownerDigits)
        assertEquals(listOf("12025550160", "0641234567"), task.withOwnerWords("064 123 4567").ownerDigits)
    }

    @Test
    fun credentialsAndPaymentsAreHandedOver() {
        val login = TestScreen()
        val password = login.add(600, null, editable = true, password = true, hint = "Password")
        val code = login.add(800, null, editable = true, hint = "Verification code")
        val manager = TestScreen("com.android.credentialmanager")
        val account = manager.add(1500, "ana@example.com")
        val shop = TestScreen(title = "Shop")
        val bar = shop.add(80, null, id = "toolbar", clickable = false)
        shop.add(90, "Checkout", parent = bar, clickable = false)
        val next = shop.add(1800, "Continue")

        assertEquals("handover:CREDENTIALS", decide(Proposal("type_text", ActionKind.TYPE, Category.TYPING_REQUEST, targetKey = password, text = "secret"), login))
        assertEquals("handover:CREDENTIALS", decide(Proposal("type_text", ActionKind.TYPE, Category.TYPING_REQUEST, targetKey = code, text = "1234"), login))
        assertEquals("handover:CREDENTIALS", decide(tap(account), manager))
        assertEquals("handover:PAYMENT", decide(tap(next), shop, everything, Mode.FULL_AUTO))
    }

    @Test
    fun typingTheRequestRunsAndTypingScreenTextOrAPlaceholderAsks() {
        val screen = TestScreen()
        val field = screen.add(1800, null, editable = true, focused = true, hint = "Message")
        val password = screen.add(600, null, editable = true, password = true, hint = "PIN")
        val task = TaskSafety.start("Send Ana a message that I'm 10 minutes late")
        fun type(text: String, key: String = field) = Proposal("type_text", ActionKind.TYPE, Category.TYPING_REQUEST, targetKey = key, text = text, frontAppLabel = "Chat")

        assertEquals("run", decide(type("I'm 10 minutes late"), screen, task))
        val screenText = SafetyCheck.decide(type("Meet at 5"), screen.snapshot(), task, standard) as Decision.Confirm
        assertEquals("Type 'Meet at 5' into 'Message' in Chat? Say confirm or cancel.", screenText.confirmation.question)
        val code = SafetyCheck.decide(type("[OTP_1]"), screen.snapshot(), task, standard.copy(mode = Mode.FULL_AUTO)) as Decision.Confirm
        assertEquals("Type 'your one-time code' into 'Message' in Chat? Say confirm or cancel.", code.confirmation.question)
        assertEquals("handover:CREDENTIALS", decide(type("[OTP_1]", password), screen, task, Mode.FULL_AUTO))
    }

    @Test
    fun anActionOutsideTheAllowanceAsksAlsoInFullAuto() {
        val screen = titled()
        val send = screen.add(1800, "Send", left = 850)

        val asked = SafetyCheck.decide(tap(send), screen.snapshot(), nothing, standard.copy(mode = Mode.FULL_AUTO)) as Decision.Confirm
        assertTrue(asked.confirmation.question.startsWith("You didn't ask me to send anything. "))
        assertEquals("run", decide(tap(send), screen, everything, Mode.FULL_AUTO))
    }

    @Test
    fun linksAreCheckedBeforeTheyOpen() {
        val screen = TestScreen("org.mozilla.firefox")
        fun link(url: String, kind: ActionKind = ActionKind.OPEN_URL, fromOwner: Boolean = false) =
            Proposal("open_url", kind, link = url, linkFromOwner = fromOwner, linkTargetPackage = "org.mozilla.firefox")

        assertEquals("block", decide(link("http://example.com"), screen))
        assertEquals("run", decide(link("https://en.wikipedia.org/wiki/Kestrel", fromOwner = true), screen))
        assertEquals(Category.SENDING, SafetyCheck.assess(link("https://evil.example/?d=483920"), screen.snapshot(), everything, standard).category)
        assertEquals("block", decide(link("tel:112", ActionKind.OPEN_DEEP_LINK), screen, everything, Mode.FULL_AUTO, SafetyEnvironment(emergency = { it == "112" })))
    }

    @Test
    fun aPointThatAnotherWindowCoversIsRefused() {
        val screen = TestScreen()
        screen.add(1500, null, clickable = false)
        screen.window(2, "com.example.keyboard", IntRect(0, 1400, 1000, 2000), WindowKind.INPUT_METHOD)

        assertEquals("block", decide(Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(500, 1550), pointText = "Next"), screen))
        assertEquals(
            Category.SENDING,
            SafetyCheck.assess(Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(500, 300), pointText = "Send"), screen.snapshot(), everything, standard).category,
        )
    }

    @Test
    fun aShareFlowStartsAtAChildOfAForwardButtonAndAtAPointOnItAndATapAtOnARowIsSelected() {
        val chat = TestScreen()
        val forward = chat.add(100, "Forward", left = 800, className = "android.widget.ImageButton")
        val icon = chat.add(120, null, parent = forward, left = 850, right = 950, height = 60, clickable = false, className = "android.widget.ImageView")
        val share = "Share this from Chat? You'll choose the chat next. Say confirm or cancel."
        fun at(x: Int, y: Int, text: String? = null) = Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(x, y), pointText = text, frontAppLabel = "Chat")

        assertEquals(ShareFlow("com.example.chat", emptyList(), null, serial = 1), SafetyCheck.afterAction(tap(icon), chat.snapshot(), chat.snapshot(), everything).shareFlow)
        assertEquals(share, (SafetyCheck.decide(tap(icon), chat.snapshot(), everything, standard) as Decision.Confirm).confirmation.question)
        val started = SafetyCheck.afterAction(at(900, 150), chat.snapshot(), chat.snapshot(), everything)
        assertEquals(1, started.shareFlow?.serial)
        assertEquals(share, (SafetyCheck.decide(at(900, 150), chat.snapshot(), everything, standard) as Decision.Confirm).confirmation.question)
        // A point that the tree does not hold, on an OCR line that reads "Forward".
        val web = TestScreen()
        assertEquals(1, SafetyCheck.afterAction(at(500, 500, "Forward"), web.snapshot(), web.snapshot(), everything).shareFlow?.serial)

        // The app's own picker: its rows send, and a row that a TapAt reaches is selected.
        val picker = TestScreen()
        val list = picker.add(300, null, className = "androidx.recyclerview.widget.RecyclerView", height = 600, clickable = false)
        val friends = picker.add(300, "Close friends", parent = list)
        picker.add(400, "Ana", parent = list)
        assertEquals("confirm", decide(tap(friends), picker, started, Mode.RELAXED))
        assertEquals(Category.SENDING, SafetyCheck.assess(at(500, 450), picker.snapshot(), started, standard).category)
        assertEquals(listOf("Ana"), SafetyCheck.afterAction(at(500, 450), picker.snapshot(), picker.snapshot(), started).shareFlow?.selected)
    }

    @Test
    fun aTapAtOnAPlainPartOfAComposingScreenDoesNotSendBecauseOfTheWindowsRoot() {
        val chat = TestScreen(title = "Ana")
        val root = chat.add(0, null, className = "android.widget.FrameLayout", height = 2000, clickable = false)
        chat.add(300, "See you at 5", parent = root, clickable = false)
        chat.add(1800, null, parent = root, editable = true, focused = true, hint = "Message", text = "I'm late", right = 850)
        chat.add(1800, null, parent = root, className = "android.widget.ImageButton", left = 850)
        fun at(x: Int, y: Int) = Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(x, y), frontAppLabel = "Chat")

        assertEquals("run", decide(at(500, 350), chat, nothing))
        assertEquals("run", decide(at(500, 1000), chat, nothing))
        val arrow = SafetyCheck.assess(at(900, 1850), chat.snapshot(), everything, standard)
        assertEquals(Category.SENDING, arrow.category)
        assertTrue(Source.COMPOSE in arrow.sources)
    }

    @Test
    fun aTapAtIsJudgedByTheAppWindowThatHoldsItsPoint() {
        val split = TestScreen("com.example.notes", bounds = IntRect(0, 0, 500, 2000))
        split.add(1000, "Notes", right = 500)
        split.window(2, "com.google.android.dialer", IntRect(500, 0, 1000, 2000), layer = 1)
        split.add(1200, null, left = 600, right = 900, className = "android.widget.ImageButton", window = 2)

        assertEquals("handover:CALL", decide(Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(750, 1250)), split, everything, Mode.FULL_AUTO))
        assertEquals("run", decide(Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(250, 1050)), split, nothing))
    }

    @Test
    fun aTapAtOnTheOtherHalfOfASplitScreenIsRefusedForAnAgentFreeAppOrPlayServices() {
        val env = SafetyEnvironment(isAgentFree = { it == "dev.kestrel" || it == "com.example.bank" })
        fun split(other: String) = TestScreen("com.example.notes", bounds = IntRect(0, 0, 500, 2000)).apply {
            add(1000, "Notes", right = 500)
            window(2, other, IntRect(500, 0, 1000, 2000), layer = 1)
            add(1200, "Details", left = 600, right = 900, window = 2)
        }
        val at = Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(750, 1250))

        for (other in listOf("com.example.bank", AgentFreeApps.PLAY_SERVICES)) {
            val assessment = SafetyCheck.assess(at, split(other).snapshot(), nothing, standard, env)
            assertEquals(other, "block", kind(assessment.decision))
            assertTrue(other, Source.COVERED in assessment.sources)
        }
        assertEquals("run", decide(at, split("com.example.shop"), nothing, env = env))
        assertEquals("run", decide(Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(250, 1050)), split("com.example.bank"), nothing, env = env))
    }

    @Test
    fun aTapAtOnAnAppWindowThatFloatsAboveTheFrontAppIsRefused() {
        fun at(x: Int, y: Int) = Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(x, y), frontAppLabel = "Chat")
        // Play services' autofill suggestion over the front app's window, which the observation leaves out.
        val autofill = TestScreen()
        autofill.add(1500, "Notes", clickable = false)
        autofill.window(2, "com.google.android.gms", IntRect(0, 1400, 1000, 1600))
        autofill.add(1450, "ana@example.com", window = 2)
        assertEquals("block", decide(at(500, 1500), autofill))
        assertEquals("run", decide(at(500, 300), autofill, nothing))
        // Another app's floating window, such as an agent-free app's.
        val floating = TestScreen()
        floating.window(2, "com.example.bank", IntRect(600, 200, 1000, 600))
        floating.add(300, "Balance", left = 600, window = 2)
        assertEquals("block", decide(at(800, 350), floating))
        // Over the activity beneath the front app's dialog, outside the dialog.
        val dialog = TestScreen()
        dialog.window(2, "com.example.chat", IntRect(100, 800, 900, 1200), active = true)
        dialog.window(3, "com.google.android.gms", IntRect(0, 1500, 1000, 1700), layer = 3)
        dialog.add(1550, "ana@example.com", window = 3)
        assertEquals("block", decide(at(500, 1600), dialog))
        assertEquals("run", decide(at(500, 300), dialog, nothing))
    }

    @Test
    fun onlyTheElementThatReceivesTheClickStartsAShareFlow() {
        val chat = TestScreen()
        val container = chat.add(100, "Share options", height = 300)
        val delete = chat.add(150, "Delete", parent = container, left = 800)
        fun at(x: Int, y: Int) = Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(x, y), frontAppLabel = "Chat")

        assertNull(SafetyCheck.afterAction(tap(delete), chat.snapshot(), chat.snapshot(), everything).shareFlow)
        assertNull(SafetyCheck.afterAction(at(900, 200), chat.snapshot(), chat.snapshot(), everything).shareFlow)
        for (proposal in listOf(tap(delete), at(900, 200))) {
            val decision = SafetyCheck.decide(proposal, chat.snapshot(), everything, standard)
            assertTrue(decision !is Decision.Confirm || !decision.confirmation.question.startsWith("Share"))
        }
        // A text that names the button that receives the click still starts a flow.
        val button = TestScreen()
        val unnamed = button.add(100, null, text = null, left = 800)
        val text = button.add(120, "Share", parent = unnamed, left = 820, height = 60, clickable = false)
        assertEquals(1, SafetyCheck.afterAction(tap(text), button.snapshot(), button.snapshot(), everything).shareFlow?.serial)
    }

    @Test
    fun aTargetThatIsGoneIsRefused() {
        assertEquals("block", decide(tap("com.example.chat|9|Gone"), TestScreen()))
    }

    @Test
    fun aPickerRowInAListThatDoesNotScrollSendsAndItsNameComesFromTheRow() {
        val picker = TestScreen("org.telegram.messenger")
        val list = picker.add(200, null, className = "androidx.recyclerview.widget.RecyclerView", height = 600, clickable = false)
        val top = picker.add(200, "Close friends", parent = list)
        val ana = picker.add(400, "Ana See you 12:30", parent = list, text = null)
        picker.add(410, "Ana", parent = ana, clickable = false, height = 40)
        val flow = everything.copy(shareFlow = ShareFlow("org.telegram.messenger", emptyList(), ShareCount(1, 0), serial = 1))

        // "Close friends" lies in the toolbar band and holds "close", but a row is never a picker's control (review 2 I1).
        assertEquals(Category.SENDING, SafetyCheck.assess(tap(top), picker.snapshot(), flow, standard).category)
        assertEquals(Category.SENDING, SafetyCheck.assess(tap(ana), picker.snapshot(), flow, standard).category)
        val selected = SafetyCheck.afterAction(tap(ana), picker.snapshot(), picker.snapshot(), flow)
        assertEquals(listOf("Ana"), selected.shareFlow?.selected)
        val chat = TestScreen("org.telegram.messenger")
        chat.add(100, "Ana", clickable = false)
        assertNull(SafetyCheck.afterAction(tap(ana), picker.snapshot(), chat.snapshot(), selected).shareFlow)
    }

    @Test
    fun anActionThatSendsAndDeletesIsAskedAsASendAndNeedsTheAllowanceToSend() {
        val screen = titled()
        val both = screen.add(1000, "Send and delete")
        val deleteOnly = TaskSafety(TaskAllowance(setOf(Category.DELETING)))

        val assessment = SafetyCheck.assess(tap(both), screen.snapshot(), deleteOnly, standard)

        assertEquals(Category.DELETING, assessment.category)
        assertTrue(assessment.sends)
        val asked = (assessment.decision as Decision.Confirm).confirmation
        assertEquals(Category.SENDING, asked.category)
        assertTrue(asked.question, asked.question.startsWith("You didn't ask me to send anything."))
    }

    @Test
    fun aClearedFieldAnAnrDialogAndAFullHeightPermissionDialog() {
        val search = TestScreen()
        val field = search.add(100, null, editable = true, hint = "Search", text = "Ana")
        assertEquals("run", decide(Proposal("type_text", ActionKind.TYPE, Category.TYPING_REQUEST, targetKey = field, text = ""), search, nothing))

        val anr = TestScreen()
        val wait = anr.add(1500, "Wait")
        anr.window(2, "android", IntRect(100, 800, 900, 1400), kind = WindowKind.SYSTEM, active = true)
        val after = SafetyCheck.afterAction(tap(wait), anr.snapshot(), anr.snapshot(), everything)
        assertNull(after.shareFlow)

        val permission = TestScreen("com.google.android.permissioncontroller")
        val allow = permission.add(1500, "While using the app", id = "permission_allow_button")
        assertEquals("handover:PERMISSION_DIALOG", decide(tap(allow), permission))
    }

    @Test
    fun afterActionKeepsTheFlowTheTrailAndTheTypedTexts() {
        val gallery = TestScreen("com.example.gallery")
        val picker = TestScreen("org.telegram.messenger")
        val list = picker.add(300, null, scrollable = true, axis = ScrollAxis.VERTICAL, height = 1600, clickable = false)
        val row = picker.add(300, "Close friends", parent = list)
        val chat = TestScreen("org.telegram.messenger")
        chat.add(100, "Close friends", clickable = false)
        val share = Proposal("share_media", ActionKind.SHARE_MEDIA, receivingPackage = "org.telegram.messenger", shareCount = ShareCount(3, 0))

        val opened = SafetyCheck.afterAction(share, gallery.snapshot(), picker.snapshot(), everything)
        assertEquals(ShareFlow("org.telegram.messenger", emptyList(), ShareCount(3, 0), serial = 1), opened.shareFlow)
        val selected = SafetyCheck.afterAction(tap(row), picker.snapshot(), picker.snapshot(), opened)
        assertEquals(listOf("Close friends"), selected.shareFlow?.selected)
        assertNull(SafetyCheck.afterAction(tap(row), picker.snapshot(), chat.snapshot(), selected).shareFlow)
        // A second share is another flow (review 4 C2), and opening a link leaves the flow (review 2 C1).
        assertEquals(2, SafetyCheck.afterAction(share, gallery.snapshot(), picker.snapshot(), selected).shareFlow?.serial)
        val link = Proposal("open_url", ActionKind.OPEN_URL, link = "https://wa.me/381600000000")
        assertNull(SafetyCheck.afterAction(link, picker.snapshot(), picker.snapshot(), selected).shareFlow)

        val security = TestScreen("com.android.settings")
        val bar = security.add(80, null, id = "action_bar", clickable = false)
        security.add(90, "Security", parent = bar, clickable = false)
        val entry = security.add(500, "Device admin apps")
        val sub = TestScreen("com.android.settings")
        val inSettings = SafetyCheck.afterAction(tap(entry), TestScreen("com.android.settings").snapshot(), security.snapshot(), everything)
        assertEquals(ProtectedTrail("Security"), inSettings.trail)
        assertEquals(ProtectedTrail("Security"), SafetyCheck.afterAction(tap(entry), security.snapshot(), sub.snapshot(), inSettings).trail)
        assertNull(SafetyCheck.afterAction(Proposal("back", ActionKind.BACK), security.snapshot(), sub.snapshot(), inSettings).trail)

        val compose = TestScreen()
        val field = compose.add(1800, null, editable = true, hint = "Message")
        val typed = SafetyCheck.afterAction(Proposal("type_text", ActionKind.TYPE, targetKey = field, text = "I'm late"), compose.snapshot(), compose.snapshot(), everything)
        assertEquals(listOf(TypedText(field, "Message", "com.example.chat", "I'm late")), typed.typed)
    }

    /**
     * A Telegram-like chat of [packageName]: a toolbar whose title block is described by [title] and [subtitle], if any, and
     * holds them, as Telegram draws it, and a Send button.
     */
    private fun telegramChat(packageName: String, title: String, subtitle: String?): Pair<TestScreen, String> {
        val screen = TestScreen(packageName)
        val bar = screen.add(0, null, height = 200)
        val block = screen.add(0, null, parent = bar, left = 150, right = 800, height = 200, description = listOfNotNull(title, subtitle).joinToString(" "))
        screen.add(80, title, id = "chat_title", parent = block, left = 150, height = 50, clickable = false)
        if (subtitle != null) screen.add(140, subtitle, parent = block, left = 150, height = 40, clickable = false)
        screen.add(1800, null, editable = true, focused = true, hint = "Message", text = "Test", right = 850)
        return screen to screen.add(1800, "Send", left = 850)
    }

    @Test
    fun testModeBlocksASendToAnyoneButTheOwnerBeforeAnyQuestion() {
        val testMode = SafetySettings()
        val (ana, send) = telegramChat("org.telegram.messenger", "Ana", "online")
        val (fixture, fixtureSend) = telegramChat("dev.kestrel.testapp", "Saved Messages", null)
        val aliases = SafetyEnvironment(selfChatAliases = mapOf("dev.kestrel.testapp" to SelfChats.TELEGRAM))

        val blocked = SafetyCheck.assess(tap(send), ana.snapshot(), everything, testMode)
        assertEquals("block", kind(blocked.decision))
        assertEquals("confirm", kind(blocked.beforeTestMode))
        assertTrue(Source.TEST_MODE in blocked.sources)
        assertEquals("Test mode: Kestrel only sends to your own chats.", (blocked.decision as Decision.Block).reason)
        assertEquals("confirm", kind(SafetyCheck.decide(tap(fixtureSend), fixture.snapshot(), everything, testMode, aliases)))
        assertEquals("block", kind(SafetyCheck.decide(tap(fixtureSend), fixture.snapshot(), everything, testMode)))
    }

    @Test
    fun testModeLetsAMailtoLinkThroughOnlyWhenEveryRecipientIsTheOwnersAndStandardModeStillAsks() {
        val testMode = SafetySettings(ownerEmails = listOf("Owner@Example.com", "me@example.org"))
        val home = TestScreen("com.google.android.apps.nexuslauncher")
        fun mail(link: String) = Proposal("open_deep_link", ActionKind.OPEN_DEEP_LINK, link = link, linkTargetPackage = "com.google.android.gm")

        for (link in listOf(
            "mailto:owner@example.com?subject=a%20reminder%20about%20heron%20maple&body=Pasted%20from%20the%20screen",
            "mailto:owner%40example.com,me@example.org?cc=OWNER@example.com&bcc=me%40example.org",
            "mailto:?to=owner@example.com",
        )) {
            val assessed = SafetyCheck.assess(mail(link), home.snapshot(), everything, testMode)
            assertEquals(link, Category.SENDING, assessed.category)
            assertEquals(link, "confirm", kind(assessed.decision))
            assertTrue(link, Source.TEST_MODE !in assessed.sources)
            assertEquals(link, "confirm", kind(SafetyCheck.decide(mail(link), home.snapshot(), everything, standard)))
        }
        for (link in listOf(
            "mailto:ana@example.com?subject=a%20reminder",
            "mailto:owner@example.com,ana@example.com?subject=a%20reminder",
            "mailto:owner@example.com?cc=ana@example.com",
            "mailto:owner@example.com?bcc=ana%40example.com",
            "mailto:owner@example.com?to=ana@example.com",
            "mailto:owner@example.com%3Fcc=ana@example.com?subject=a%20reminder",
            "mailto:owner@example.com?x-cc=ana@example.com",
            "mailto:Ana%20Petrovi%C4%87?subject=a%20reminder",
            "mailto:?subject=a%20reminder",
            "mailto:owner@example.com?subject=a%20reminder#cc=ana@example.com",
            // android.net.MailTo.parse decodes the whole query before it splits it, and reads these as a Cc and a To.
            "mailto:owner@example.com?body=hi%26cc%3Dana%40example.com",
            "mailto:owner@example.com?subject=a%20reminder&body=x%26to%3Dana@example.com",
        )) {
            assertEquals(link, "Test mode: Kestrel only sends to your own chats.", (SafetyCheck.decide(mail(link), home.snapshot(), everything, testMode) as Decision.Block).reason)
        }
        assertEquals("block", kind(SafetyCheck.decide(mail("mailto:owner@example.com?subject=a%20reminder"), home.snapshot(), everything, SafetySettings())))
    }

    @Test
    fun testModeJudgesALinkByWhereItGoesNotByTheOwnersChatInFront() {
        val testMode = SafetySettings(ownerEmails = listOf("owner@example.com"))
        val (fixture, fixtureSend) = telegramChat("dev.kestrel.testapp", "Saved Messages", null)
        val aliases = SafetyEnvironment(selfChatAliases = mapOf("dev.kestrel.testapp" to SelfChats.TELEGRAM))
        fun link(url: String, target: String) =
            Proposal("open_link", if (url.startsWith("https")) ActionKind.OPEN_URL else ActionKind.OPEN_DEEP_LINK, link = url, linkTargetPackage = target)

        for (proposal in listOf(
            link("https://evil.example/c?d=Pasted%20from%20the%20screen", "com.android.chrome"),
            link("tg://msg?text=x&to=%2B12025550143", "org.telegram.messenger"),
            link("sms:+12025550143?body=Pasted%20from%20the%20screen", "com.google.android.apps.messaging"),
        )) {
            val assessed = SafetyCheck.assess(proposal, fixture.snapshot(), everything, testMode, aliases)
            assertEquals(proposal.link, Category.SENDING, assessed.category)
            assertEquals(proposal.link, "Test mode: Kestrel only sends to your own chats.", (assessed.decision as Decision.Block).reason)
            assertEquals(proposal.link, false, SafetyCheck.testModeAllows(proposal, fixture.snapshot(), everything, testMode, aliases))
        }
        val own = link("mailto:owner@example.com?body=Pasted%20from%20the%20screen", "com.google.android.gm")
        assertEquals("confirm", kind(SafetyCheck.decide(own, fixture.snapshot(), everything, testMode, aliases)))
        assertEquals(true, SafetyCheck.testModeAllows(own, fixture.snapshot(), everything, testMode, aliases))
        assertEquals("confirm", kind(SafetyCheck.decide(tap(fixtureSend), fixture.snapshot(), everything, testMode, aliases)))
    }

    @Test
    fun aGeoOrHttpsLinkWhoseQueryHoldsOnlyTheOwnersWordsOrAMemoryValueReadsInEveryMode() {
        val home = TestScreen("com.google.android.apps.nexuslauncher")
        fun link(url: String, target: String) =
            Proposal("open_link", if (url.startsWith("https")) ActionKind.OPEN_URL else ActionKind.OPEN_DEEP_LINK, link = url, linkTargetPackage = target)
        val maps = TaskSafety.start("show Kalemegdan on Google Maps")
        val youtube = TaskSafety.start("search YouTube for kestrel hovering")
        val remembered = TaskSafety.start("show my gym on Google Maps").copy(memoryValues = listOf("Knez Mihailova 5"))

        for ((proposal, task) in listOf(
            link("geo:0,0?q=Kalemegdan", "com.google.android.apps.maps") to maps,
            link("https://www.youtube.com/results?search_query=kestrel+hovering", "com.google.android.youtube") to youtube,
            link("geo:0,0?q=Knez+Mihailova+5", "com.google.android.apps.maps") to remembered,
        )) {
            assertEquals(proposal.link, Category.READING, SafetyCheck.assess(proposal, home.snapshot(), task, standard).category)
            assertEquals(proposal.link, Decision.Run, SafetyCheck.decide(proposal, home.snapshot(), task, SafetySettings()))
        }
        // A word from the screen, such as an injected link's code, keeps the link sending, and test mode blocks it.
        for ((proposal, task) in listOf(
            link("geo:0,0?q=Kalemegdan%20Petrovi%C4%87", "com.google.android.apps.maps") to maps,
            link("https://evil.example/results?search_query=kestrel+hovering&code=483920", "org.mozilla.firefox") to youtube,
            link("https://t.me/share/url?url=kestrel&text=hovering", "org.telegram.messenger") to youtube,
        )) {
            assertEquals(proposal.link, Category.SENDING, SafetyCheck.assess(proposal, home.snapshot(), task, standard).category)
            assertEquals(proposal.link, "block", kind(SafetyCheck.decide(proposal, home.snapshot(), task, SafetySettings())))
        }
    }

    @Test
    fun testModeBlocksTheAgentsCallsAndTextMessages() {
        val testMode = SafetySettings(mode = Mode.FULL_AUTO)
        val dialer = TestScreen("com.google.android.dialer")
        val call = dialer.add(1800, "Call")
        val sms = Proposal("compose_sms", ActionKind.COMPOSE_SMS, receivingPackage = "com.google.android.apps.messaging")

        val assessed = SafetyCheck.assess(tap(call), dialer.snapshot(), everything, testMode)
        assertEquals("handover:CALL", kind(assessed.beforeTestMode))
        assertEquals("block", kind(assessed.decision))
        assertEquals("block", kind(SafetyCheck.decide(sms, TestScreen().snapshot(), everything, testMode)))
    }

    @Test
    fun theCheckAfterTheYesRepeatsTestMode() {
        val (ana, send) = telegramChat("org.telegram.messenger", "Ana", "online")
        val (fixture, fixtureSend) = telegramChat("dev.kestrel.testapp", "Saved Messages", null)
        val aliases = SafetyEnvironment(selfChatAliases = mapOf("dev.kestrel.testapp" to SelfChats.TELEGRAM))

        assertEquals(false, SafetyCheck.testModeAllows(tap(send), ana.snapshot(), everything, SafetySettings()))
        assertEquals(true, SafetyCheck.testModeAllows(tap(send), ana.snapshot(), everything, standard))
        assertEquals(true, SafetyCheck.testModeAllows(tap(fixtureSend), fixture.snapshot(), everything, SafetySettings(), aliases))
        // A target that is gone is left to the loop's check of the confirmation, which asks again (review 2-2 New 4).
        assertEquals(true, SafetyCheck.testModeAllows(tap("org.telegram.messenger|9|Gone"), ana.snapshot(), everything, SafetySettings()))
    }

    @Test
    fun testModeChecksEverySendWhateverItsCategoryAndTheChatOnTheScreenNotTheFlow() {
        val testMode = SafetySettings()
        val aliases = SafetyEnvironment(selfChatAliases = mapOf("dev.kestrel.testapp" to SelfChats.TELEGRAM))
        val (ana, _) = telegramChat("dev.kestrel.testapp", "Ana", "online")
        val both = ana.add(1000, "Send and delete")
        val (marko, markoSend) = telegramChat("dev.kestrel.testapp", "Marko", "online")
        val inFlow = everything.copy(shareFlow = ShareFlow("dev.kestrel.testapp", listOf("Saved Messages"), ShareCount(1, 0), serial = 1))

        val sendAndDelete = SafetyCheck.assess(tap(both), ana.snapshot(), everything, testMode, aliases)
        assertEquals(Category.DELETING, sendAndDelete.category)
        assertEquals("block", kind(sendAndDelete.decision))
        assertEquals("block", kind(SafetyCheck.decide(tap(markoSend), marko.snapshot(), inFlow, testMode, aliases)))
        assertEquals(false, SafetyCheck.testModeAllows(tap(markoSend), marko.snapshot(), inFlow, testMode, aliases))
    }

    @Test
    fun testModeJudgesATapAtByTheRowUnderItsPoint() {
        val testMode = SafetySettings()
        val aliases = SafetyEnvironment(selfChatAliases = mapOf("dev.kestrel.testapp" to SelfChats.TELEGRAM))
        val picker = TestScreen("dev.kestrel.testapp")
        val list = picker.add(300, null, className = "androidx.recyclerview.widget.RecyclerView", height = 600, clickable = false)
        val saved = picker.add(300, null, parent = list, text = null)
        picker.add(300, "Saved Messages", parent = saved, clickable = false, right = 800, height = 50)
        picker.add(350, "Forward here to save.", parent = saved, clickable = false, right = 800, height = 50)
        picker.add(300, null, parent = saved, className = "android.widget.CheckBox", checkable = true, checked = true, left = 900, height = 50)
        val ana = picker.add(400, "Ana", parent = list)
        val inFlow = everything.copy(shareFlow = ShareFlow("dev.kestrel.testapp", emptyList(), ShareCount(1, 0), serial = 1))
        val atAna = Proposal("tap", ActionKind.TAP_AT, Category.READING, point = Point(500, 450), frontAppLabel = "Chat")

        assertEquals("block", kind(SafetyCheck.decide(tap(ana), picker.snapshot(), inFlow, testMode, aliases)))
        assertEquals("block", kind(SafetyCheck.decide(atAna, picker.snapshot(), inFlow, testMode, aliases)))
        assertEquals(false, SafetyCheck.testModeAllows(atAna, picker.snapshot(), inFlow, testMode, aliases))
    }
}
