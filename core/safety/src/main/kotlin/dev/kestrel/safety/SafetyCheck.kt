package dev.kestrel.safety

import dev.kestrel.commands.text.Normalizer
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.ScreenTitle
import dev.kestrel.screen.model.ScreenTree
import dev.kestrel.screen.model.WindowSummary

/**
 * The safety check (design D13 of sub-project 5; spec 12.1 to 12.3): a pure function of a proposal, the fresh snapshot,
 * the task's state and the settings, called for every action that the agent or a routine proposes, before anything runs.
 * The action's category is the most restrictive that any source suggests: the declared or fixed category, the words of
 * the label that the action reaches, and the context rules. The mode decides run or confirm per row; the floor (payments
 * and credentials hand over, protected blocks) holds in every mode, every call of the agent is handed over, and an action
 * outside the task's allowance is confirmed in every mode, as is typing a placeholder.
 */
object SafetyCheck {
    // set_slider acts on an element as a tap does: the model declares its category, and the context rules and the label
    // words reach it (design D19 of sub-project 6).
    private val ACTING = setOf(ActionKind.TAP, ActionKind.LONG_PRESS, ActionKind.TAP_AT, ActionKind.TYPE, ActionKind.SWIPE, ActionKind.SET_SLIDER)
    private val PRESSING = setOf(ActionKind.TAP, ActionKind.LONG_PRESS, ActionKind.TAP_AT)
    private val DECLARING = setOf(
        ActionKind.TAP, ActionKind.LONG_PRESS, ActionKind.TAP_AT, ActionKind.TYPE, ActionKind.SCROLL, ActionKind.SWIPE, ActionKind.SET_SLIDER,
    )
    private val LEAVING = setOf(ActionKind.OPEN_APP, ActionKind.OPEN_URL, ActionKind.OPEN_DEEP_LINK, ActionKind.OPEN_SETTINGS, ActionKind.HOME)
    private const val CHANGED = "The screen changed before the action; nothing was done."
    private const val COVERED = "Another window covers that point."
    private const val CLOSED_APP = "That point is in an app that the agent may not use."

    fun decide(proposal: Proposal, screen: ScreenSnapshot, task: TaskSafety, settings: SafetySettings, env: SafetyEnvironment = SafetyEnvironment()): Decision =
        assess(proposal, screen, task, settings, env).decision

    fun assess(proposal: Proposal, screen: ScreenSnapshot, task: TaskSafety, settings: SafetySettings, env: SafetyEnvironment = SafetyEnvironment()): Assessment {
        val sources = LinkedHashSet<Source>()
        var category = Category.READING
        // Whether any source suggests sending, also when a higher category wins (review 2 I2).
        var sends = false
        fun raise(suggested: Category?, source: Source) {
            if (suggested == null) return
            sources += source
            if (suggested == Category.SENDING) sends = true
            if (suggested > category) category = suggested
        }
        fun ending(decision: Decision) = Assessment(category, sources, decision, decision, sends)

        val index = proposal.targetKey?.let { key ->
            screen.elements.indexOfFirst { it.key == key }.takeIf { it >= 0 } ?: return ending(Decision.Block(CHANGED))
        }
        val point = proposal.point
        if (point != null && ContextRules.covered(screen, point)) {
            sources += Source.COVERED
            return ending(Decision.Block(COVERED))
        }
        // A point in an agent-free app's window or in Play services', such as the other half of a split screen, which the
        // observation leaves out (design D13 and D16).
        if (point != null && ContextRules.windowAt(screen, point)?.packageName.let { env.isAgentFree(it) || it == AgentFreeApps.PLAY_SERVICES }) {
            sources += Source.COVERED
            return ending(Decision.Block(CLOSED_APP))
        }
        val atPoint = point?.let { ContextRules.atPoint(screen, it) }.orEmpty()
        val reached = listOfNotNull(index) + atPoint
        // The element that the press lands on: a tap's target, or the topmost clickable element under a TapAt's point.
        val pressed = ContextRules.pressed(screen, index, point)
        val window = if (index == null && point != null) ContextRules.windowAt(screen, point) else windowOf(screen, index)

        // 1. The declared category, or the tool's fixed one.
        if (proposal.kind in DECLARING) raise(proposal.declared ?: Category.READING, Source.DECLARED) else raise(fixed(proposal.kind), Source.FIXED)

        // 2. The words of the labels that the action reaches, and of their clickable ancestors.
        if (proposal.kind in PRESSING || proposal.kind == ActionKind.SWIPE || proposal.kind == ActionKind.SET_SLIDER) {
            val clears = index != null && ContextRules.clearsAField(screen, index)
            val names = reached.flatMap { at -> listOf(at) + ScreenTree.ancestors(screen, at).filter { screen.elements[it].clickable } }
                .map { ScreenTree.name(screen.elements[it]) } + proposal.pointText
            val words = names.mapNotNull { LabelWords.categoryOf(it) }.maxOrNull()
            raise(if (clears && words == Category.DELETING) Category.TYPING_REQUEST else words, Source.LABEL_WORDS)
            if (names.any { LabelWords.holds(it, Category.SENDING) }) sends = true
            if (reached.any { EmergencyGuard.emergencyNamed(screen, it) } || EmergencyGuard.namesEmergency(proposal.pointText)) {
                raise(Category.CALLS, Source.PHONE_APP)
            }
        }

        // 3. The context rules of the window that the action reaches.
        if (window != null && proposal.kind in ACTING) {
            if (ContextRules.isPermissionDialog(screen, window)) {
                sources += Source.PROTECTED_SCREEN
                return ending(Decision.HandOver(HandOver.of(HandOverKind.PERMISSION_DIALOG)))
            }
            if (window.packageName == ContextRules.CREDENTIAL_MANAGER) {
                sources += Source.CREDENTIALS
                return ending(Decision.HandOver(HandOver.of(HandOverKind.CREDENTIALS)))
            }
            if (ContextRules.isProtected(screen, window, task.trail)) raise(Category.PROTECTED, Source.PROTECTED_SCREEN)
            if (ContextRules.isPhoneApp(window.packageName, env.phonePackages)) raise(Category.CALLS, Source.PHONE_APP)
            if (reached.any { ContextRules.isCredential(screen, it, window) }) raise(Category.CREDENTIALS, Source.CREDENTIALS)
            // A slider on a payment screen is a payment too, clickable or not (design D19 of sub-project 6).
            if (ContextRules.isPayment(screen, window) &&
                (proposal.kind == ActionKind.SET_SLIDER || (proposal.kind in PRESSING && reached.any { screen.elements[it].clickable }))
            ) {
                raise(Category.PAYMENTS, Source.PAYMENTS)
            }
            if (proposal.kind in PRESSING && pressed != null && ContextRules.nearCompose(screen, pressed, point.takeIf { index == null })) {
                raise(Category.SENDING, Source.COMPOSE)
            }
            val flow = task.shareFlow
            val inFlow = flow != null && (window.packageName == flow.receivingPackage || ContextRules.isShareSheet(screen, window))
            if (proposal.kind in PRESSING && (inFlow || ContextRules.shareSheetInFront(screen))) {
                val control = index != null && ContextRules.pickerControl(screen, index, window)
                if (!control) raise(Category.SENDING, Source.SHARE_FLOW)
            }
        }

        // Typing: the request's words or memory's values, else text from the screen.
        if (proposal.kind == ActionKind.TYPE) raise(typing(proposal.text.orEmpty(), task), Source.TYPING)
        if (proposal.kind == ActionKind.ADD_CONTACT) raise(contactTyping(proposal.contactName.orEmpty(), proposal.contactNumber.orEmpty(), task), Source.TYPING)

        // Links.
        if (proposal.kind == ActionKind.OPEN_URL || proposal.kind == ActionKind.OPEN_DEEP_LINK) {
            when (val verdict = LinkCheck.check(proposal.link.orEmpty(), proposal.kind, proposal.linkFromOwner, proposal.linkTargetPackage, env.isAgentFree, env.emergency, known(task))) {
                is LinkVerdict.Refused -> {
                    sources += Source.LINK
                    return ending(Decision.Block(verdict.reason))
                }
                is LinkVerdict.Allowed -> raise(verdict.category, Source.LINK)
            }
        }

        val before = decision(proposal, screen, category, task, settings.mode, sends)
        if (Placeholders.holdsOne(proposal.text)) sources += Source.PLACEHOLDER
        // Test mode turns a send to anyone but the owner, every call and a text message into Block, before any question.
        val reason = if (settings.testMode) testModeBlock(proposal, screen, category, sends, task, settings, env) else null
        if (reason != null) sources += Source.TEST_MODE
        return Assessment(category, sources, before, reason?.let { Decision.Block(it) } ?: before, sends)
    }

    /**
     * Test mode's check alone, on the fresh snapshot after the owner's yes (design D14 and D18): true when test mode is off
     * or allows the action. A target that is gone is not test mode's to judge: the loop's check that the confirmation is
     * still the same refuses the press and asks again (review 2-2 New 4).
     */
    fun testModeAllows(proposal: Proposal, screen: ScreenSnapshot, task: TaskSafety, settings: SafetySettings, env: SafetyEnvironment = SafetyEnvironment()): Boolean {
        if (!settings.testMode) return true
        val assessment = assess(proposal, screen, task, settings.copy(testMode = false), env)
        return testModeBlock(proposal, screen, assessment.category, assessment.sends, task, settings, env) == null
    }

    /**
     * Whether the front window is an e-mail's compose screen, which shows To, Cc or Bcc fields (design D14): its recipients
     * are those fields, and only its own Send sends it.
     */
    fun showsEmailCompose(screen: ScreenSnapshot): Boolean =
        ScreenTree.frontWindow(screen)?.let { ContextRules.recipientFields(screen, it.id).isNotEmpty() } == true

    /**
     * Test mode's block: every call, every text message, and every action that any source rates as sending, whatever its
     * final category ("Send and delete"), unless it sends to the owner's own chat (review 2 I2), or it is a `mailto:` link
     * to his own addresses alone, which opens a compose screen whose Send test mode checks again.
     */
    private fun testModeBlock(
        proposal: Proposal,
        screen: ScreenSnapshot,
        category: Category,
        sends: Boolean,
        task: TaskSafety,
        settings: SafetySettings,
        env: SafetyEnvironment,
    ): String? =
        when {
            category == Category.CALLS -> "Test mode: Kestrel makes no calls."
            proposal.kind == ActionKind.COMPOSE_SMS -> "Test mode: Kestrel sends no text messages."
            (sends || category == Category.SENDING) && !SelfChats.recognise(screen, pressedKey(proposal, screen), settings, task.shareFlow, env.selfChatAliases) &&
                !(proposal.kind == ActionKind.OPEN_DEEP_LINK && SelfChats.mailtoToOwner(proposal.link.orEmpty(), settings)) ->
                "Test mode: Kestrel only sends to your own chats."
            else -> null
        }

    /** The key of the element that a press lands on, so that test mode reads the row under a `TapAt`'s point too. */
    private fun pressedKey(proposal: Proposal, screen: ScreenSnapshot): String? =
        proposal.targetKey ?: proposal.point?.let { ContextRules.underPoint(screen, it) }?.let { screen.elements[it].key }

    /**
     * Updates the task's state after an action ran (design D13): a typed text is remembered (pass the proposal with the
     * text that was typed, the vault's values revealed), a share flow starts with the next serial number (also at a press
     * on an element inside a Share or Forward button, or at a point on one), grows by the name of every row that a tap or
     * a tap at a point reached in its list of rows (never by a target of Android's share sheet, which is an
     * app), and ends at a chat titled as a selected row, at an e-mail's compose screen, after `open_*` or `home`, or
     * outside its apps, and a protected trail starts and ends.
     */
    fun afterAction(proposal: Proposal, before: ScreenSnapshot, after: ScreenSnapshot, task: TaskSafety): TaskSafety {
        var next = task
        val index = proposal.targetKey?.let { key -> before.elements.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
        val element = index?.let { before.elements[it] }
        if (proposal.kind == ActionKind.TYPE && element != null) {
            val typed = TypedText(element.key, element.hint ?: element.contentDescription ?: element.label, element.packageName, proposal.text.orEmpty())
            next = next.copy(typed = next.typed.filterNot { it.fieldKey == element.key } + typed)
        }
        val flow = flowAfter(proposal, before, after, index, next.shareFlow, next.flows + 1)
        next = next.copy(shareFlow = flow, flows = maxOf(next.flows, flow?.serial ?: 0))
        next = next.copy(trail = trailAfter(proposal, before, after, next.trail))
        return next
    }

    private fun flowAfter(proposal: Proposal, before: ScreenSnapshot, after: ScreenSnapshot, index: Int?, flow: ShareFlow?, serial: Int): ShareFlow? {
        val front = ScreenTree.frontWindow(after)?.packageName ?: return flow
        var current = flow
        if (proposal.kind == ActionKind.SHARE_MEDIA || proposal.kind == ActionKind.COMPOSE_SMS) {
            return ShareFlow(proposal.receivingPackage ?: front, emptyList(), proposal.shareCount, serial)
        }
        // Opening something else or going home leaves the flow, so that its selection never names another chat (review 2 C1).
        if (proposal.kind in LEAVING) current = null
        val pressed = ContextRules.pressed(before, index, proposal.point)
        val sheet = pressed?.let { before.windows.firstOrNull { w -> w.id == before.elements[it].windowId } }?.let { ContextRules.isShareSheet(before, it) } == true
        if (current != null && proposal.kind in PRESSING && pressed != null && !sheet) {
            val row = ContextRules.row(before, pressed)?.let { ContextRules.rowName(before, it) }
            if (row != null && row !in current.selected) current = current.copy(selected = current.selected + row)
        }
        if (current == null && proposal.kind in PRESSING && ContextRules.startsShare(before, pressed, proposal.pointText)) {
            current = ShareFlow(front, emptyList(), null, serial)
        }
        if (current == null && ContextRules.shareSheetInFront(after)) current = ShareFlow(front, emptyList(), null, serial)
        current ?: return null
        // An e-mail's compose screen (after Gmail's Forward, or a share into a mail app) is judged by the e-mail's own rules:
        // its To, Cc and Bcc name the recipients, and its other taps send nothing (design D14).
        if (showsEmailCompose(after)) return null
        // From a share sheet into the app that the owner's task shares with.
        if (current.receivingPackage in ContextRules.SHARE_SHEETS && front !in ContextRules.SHARE_SHEETS) current = current.copy(receivingPackage = front)
        if (front != current.receivingPackage && front !in ContextRules.SHARE_SHEETS) return null
        val window = ScreenTree.frontWindow(after) ?: return current
        val title = ScreenTitle.of(after, window, proposal.receivingAppLabel ?: proposal.frontAppLabel, withRule3 = false)
        if (title != null && current.selected.any { Normalizer.normalize(it) == Normalizer.normalize(title) }) return null
        return current
    }

    private fun trailAfter(proposal: Proposal, before: ScreenSnapshot, after: ScreenSnapshot, trail: ProtectedTrail?): ProtectedTrail? {
        if (ScreenTree.frontWindow(after)?.packageName != ContextRules.SETTINGS) return null
        if (trail != null && proposal.kind == ActionKind.BACK) {
            val left = ScreenTree.frontWindow(before)?.let { ContextRules.heading(before, it) }
            if (left != null && Normalizer.normalize(left) == Normalizer.normalize(trail.startTitle)) return null
        }
        return trail ?: ContextRules.trailHeading(after)?.let { ProtectedTrail(it) }
    }


    private fun windowOf(snapshot: ScreenSnapshot, index: Int?): WindowSummary? {
        val id = index?.let { snapshot.elements[it].windowId } ?: return ScreenTree.frontWindow(snapshot)
        return snapshot.windows.firstOrNull { it.id == id } ?: ScreenTree.frontWindow(snapshot)
    }

    /** The tools whose category code fixes (design D13, review A I7). */
    private fun fixed(kind: ActionKind): Category = when (kind) {
        ActionKind.SETTINGS_DATA -> Category.ORDINARY_SETTINGS
        ActionKind.DELETE_CONTACT -> Category.DELETING
        else -> Category.READING
    }

    /**
     * `add_contact` types the request when every word of the name is among the owner's words or a memory value that the task
     * used, and the number's digits occur in a row among the digits of the owner's texts or of those values; otherwise it
     * types screen text, which Standard mode asks about, because a contact from a screen could make a later "call <name>"
     * reach a stranger (design D19 of sub-project 6).
     */
    private fun contactTyping(name: String, number: String, task: TaskSafety): Category {
        val words = Normalizer.words(name)
        val known = known(task)
        val digits = TaskSafety.digitsOf(number)
        val sources = task.ownerDigits + task.memoryValues.map(TaskSafety::digitsOf)
        val named = words.isNotEmpty() && words.all { it in known }
        val numbered = digits.isNotEmpty() && sources.any { digits in it }
        return if (named && numbered) Category.TYPING_REQUEST else Category.TYPING_SCREEN_TEXT
    }

    /**
     * Typing the request: every word occurs in the owner's words or in a memory value that the task used. Clearing a field
     * (a blank text) types nothing from the screen (review 2 M6).
     */
    private fun typing(text: String, task: TaskSafety): Category {
        if (text.isBlank()) return Category.TYPING_REQUEST
        val words = Normalizer.words(text)
        val known = known(task)
        return if (words.isNotEmpty() && words.all { it in known } && !Placeholders.holdsOne(text)) Category.TYPING_REQUEST else Category.TYPING_SCREEN_TEXT
    }

    /** The words that the owner gave the task: those of his request and answers, and of the memory values that it used. */
    private fun known(task: TaskSafety): Set<String> = task.ownerWords + task.memoryValues.flatMap { Normalizer.words(it) }

    /**
     * The mode's answer for [category], then the floor, the allowance and the placeholder rule. An action that [sends] and
     * deletes is asked about as a send, which names its recipient (the two rows have the same modes), and needs the
     * allowance to send too (review 2 I2).
     */
    private fun decision(proposal: Proposal, screen: ScreenSnapshot, category: Category, task: TaskSafety, mode: Mode, sends: Boolean): Decision {
        val asked = if (sends && category == Category.DELETING) Category.SENDING else category
        val confirm = { why: String? -> ConfirmationBuilder.build(proposal, screen, asked, task, why) }
        when (category) {
            Category.CALLS -> return Decision.HandOver(HandOver.of(HandOverKind.CALL))
            Category.PAYMENTS -> return Decision.HandOver(HandOver.of(HandOverKind.PAYMENT))
            Category.CREDENTIALS -> return Decision.HandOver(HandOver.of(HandOverKind.CREDENTIALS))
            Category.PROTECTED -> return Decision.Block("This is a protected screen, where Kestrel never acts.")
            else -> Unit
        }
        // share_media and compose_sms only open a screen, but they need the allowance to send (design D13, review B2-M1).
        if ((proposal.kind == ActionKind.SHARE_MEDIA || proposal.kind == ActionKind.COMPOSE_SMS) && !task.allowance.allows(Category.SENDING)) {
            return confirm(if (proposal.kind == ActionKind.SHARE_MEDIA) "You didn't ask me to share anything." else "You didn't ask me to send anything.")
        }
        if (sends && category == Category.DELETING && !task.allowance.allows(Category.SENDING)) return confirm(outside(Category.SENDING))
        if (!task.allowance.allows(category)) return confirm(outside(category))
        if (Placeholders.holdsOne(proposal.text)) return confirm(null)
        val runs = when (category) {
            Category.READING, Category.TYPING_REQUEST, Category.ORDINARY_SETTINGS -> true
            Category.TYPING_SCREEN_TEXT -> mode != Mode.STANDARD
            else -> mode == Mode.FULL_AUTO
        }
        return if (runs) Decision.Run else confirm(null)
    }

    private fun outside(category: Category): String = when (category) {
        Category.SENDING -> "You didn't ask me to send anything."
        Category.DELETING -> "You didn't ask me to delete anything."
        Category.TYPING_SCREEN_TEXT -> "You didn't ask me to copy text from the screen."
        else -> "You didn't ask me to do this."
    }
}
