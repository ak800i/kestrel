package dev.kestrel.app.commands

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityNodeInfo
import dev.kestrel.app.listen.AndroidCallMonitor
import dev.kestrel.app.listen.CallMonitor
import dev.kestrel.app.orchestrator.CallState
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.commands.CallTarget
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Direction
import dev.kestrel.commands.Edge
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.commands.Target
import dev.kestrel.commands.TextCommand
import dev.kestrel.commands.phone.ContactMatch
import dev.kestrel.commands.phone.ContactMatcher
import dev.kestrel.commands.phone.NumberChoice
import dev.kestrel.commands.phone.PhoneEntry
import dev.kestrel.commands.phone.PhoneNumbers
import dev.kestrel.commands.text.Normalizer
import dev.kestrel.commands.text.Strictness
import dev.kestrel.screen.ScreenSession
import dev.kestrel.screen.act.ActionResult
import dev.kestrel.screen.act.GesturePaths
import dev.kestrel.screen.act.ScreenEdge
import dev.kestrel.screen.act.ScrollDirection
import dev.kestrel.screen.model.IntRect
import dev.kestrel.screen.model.ScreenElement
import dev.kestrel.screen.model.ScreenSnapshot
import dev.kestrel.screen.model.WindowKind
import dev.kestrel.screen.read.Labels
import dev.kestrel.screen.read.LiveSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How a press of the phone app's call button ended. */
internal enum class Pressed { CALLING, CHOOSE_SIM, REFUSED, OTHER_NUMBER }

/**
 * What "call X" does with a number (design D11 and the controller's decisions): [CALL] after the countdown, [EMERGENCY]
 * after the owner's "yes, call" (or not at all under [EmergencyPolicy.TOUCH_ONLY]), and [TAP_YOURSELF] never.
 */
internal enum class DialRule { CALL, EMERGENCY, TAP_YOURSELF }

/** The default phone app as "call X" uses it; a fake in the JVM tests. */
internal interface Dialer {
    /** What Kestrel does with [digits]; see [DialRule]. */
    fun rule(digits: String): DialRule

    /** Opens the default phone app with [digits]; false when it could not. */
    suspend fun open(digits: String): Boolean

    /** Waits for the phone app to show, also after its cold start, and then up to 3 s for its call button; false when none shows. */
    suspend fun awaitCallButton(): Boolean

    /**
     * Presses the call button, unless the phone app already shows the call, or its number field, as the phone app reports
     * it right before the press, shows anything but [digits], which Kestrel announced.
     */
    suspend fun press(digits: String): Pressed

    /**
     * Waits up to 5 s after a press for the call to start, as Android's call state shows it: true once it shows a call,
     * false when none started, and null when Kestrel cannot see the call state, without READ_PHONE_STATE. The 5 s count
     * from the end of [press], which includes its wait of up to 1.5 s for a SIM chooser, so up to 6.5 s after the click.
     */
    suspend fun callStarted(): Boolean?
}

/**
 * "Call [number]" and "call [name]" (design spec, section 13.6; decision 1). Kestrel never requests `CALL_PHONE`: it opens
 * the default phone app with the number, says whom it calls, counts down 4 s from the end of that announcement on the bar
 * and presses the phone app's call button; when Android's call state shows no call within 5 s of the end of the press
 * (which includes the wait of up to 1.5 s for a SIM chooser), the command fails and Kestrel says so. "Cancel", "stop" or "no", or a touch, stops the countdown, and the phone app
 * stays open with the number. For an emergency number Kestrel follows [policy]: it asks for "yes, call" after the
 * announcement instead of the countdown, or only opens the phone app. For a number with fewer than 5 digits or one with
 * `*` or `#`, Kestrel only opens the phone app. A contact's number keeps its pause, wait or extension, which the phone app sends after the
 * call has started. Contacts come from [contacts], which needs `READ_CONTACTS`; without it Kestrel opens its settings app,
 * which asks for it. A call is never repeated by "again".
 */
internal class CallCommands(
    private val contacts: ContactsReader,
    private val dialer: Dialer,
    private val openSettings: (SettingsPage?, String?) -> Unit,
    private val policy: EmergencyPolicy = EmergencyPolicy.CURRENT,
) {
    suspend fun call(who: CallTarget, conversation: CommandConversation): CommandResult {
        val (digits, whom) = when (who) {
            is CallTarget.Number -> who.digits to PhoneCommandTexts.spoken(who.digits)
            is CallTarget.Name -> when (val found = contact(who, conversation)) {
                is Found.Number -> found.digits to found.whom
                is Found.Ends -> return found.result
            }
        }
        val rule = dialer.rule(digits)
        if (rule == DialRule.TAP_YOURSELF || rule == DialRule.EMERGENCY && policy == EmergencyPolicy.TOUCH_ONLY) {
            if (!dialer.open(digits)) return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_PHONE_APP)
            return CommandResult(Outcome.DONE, say = PhoneCommandTexts.tapCallYourself(whom))
        }
        if (!dialer.open(digits)) return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_PHONE_APP)
        if (!dialer.awaitCallButton()) return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.OPENED_TAP_CALL)
        conversation.say(PhoneCommandTexts.calling(whom))
        if (rule == DialRule.EMERGENCY) {
            // Never through the countdown alone: an emergency number needs the owner's "yes, call".
            EmergencyGuard.confirm(policy, conversation)?.let { return it }
        } else if (!conversation.countdown(COUNTDOWN_MILLIS) { PhoneCommandTexts.callingIn(whom, it) }) {
            // The owner's speech holds the countdown until its transcript decides; a touch or 30 s of holds give up.
            return CommandResult(Outcome.CANCELLED)
        }
        return when (dialer.press(digits)) {
            // The phone app can take the click and still call no one, as Google's calls an empty number, which Android
            // refuses without a word, so the call must start within 5 s of the end of the press.
            Pressed.CALLING -> if (dialer.callStarted() == false) {
                CommandResult(Outcome.FAILED, say = PhoneCommandTexts.CALL_NOT_STARTED)
            } else {
                CommandResult(Outcome.DONE)
            }
            Pressed.CHOOSE_SIM -> CommandResult(Outcome.DONE, show = PhoneCommandTexts.CHOOSE_SIM)
            Pressed.REFUSED -> CommandResult(Outcome.FAILED, say = PhoneCommandTexts.COULD_NOT_PRESS)
            Pressed.OTHER_NUMBER -> CommandResult(Outcome.FAILED, say = PhoneCommandTexts.OTHER_NUMBER)
        }
    }

    private sealed interface Found {
        /** The number to call, and whom Kestrel names: "Ana Petrović, mobile". */
        class Number(val digits: String, val whom: String) : Found

        class Ends(val result: CommandResult) : Found
    }

    private suspend fun contact(who: CallTarget.Name, conversation: CommandConversation): Found {
        val all = contacts.contacts() ?: run {
            openSettings(null, Manifest.permission.READ_CONTACTS)
            return Found.Ends(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NEED_CONTACTS))
        }
        // Only contacts that Kestrel can call are offered; a contact without a number is found only when no other matches.
        val callable = all.filter { it.numbers.isNotEmpty() }
        val match = ContactMatcher.find(who.name, callable).takeUnless { it == ContactMatch.None } ?: ContactMatcher.find(who.name, all)
        val contact = when (match) {
            is ContactMatch.One -> match.contact
            is ContactMatch.Several -> {
                val names = match.contacts.map { it.names.first() }
                when (val answer = askAmong(conversation, PhoneCommandTexts.whichOne(names), names)) {
                    is Answer.Chosen -> match.contacts[answer.index]
                    is Answer.Ends -> return Found.Ends(answer.result)
                }
            }
            is ContactMatch.TooMany ->
                return Found.Ends(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.tooManyContacts(match.count, who.name)))
            ContactMatch.None -> return Found.Ends(CommandResult(Outcome.FAILED, say = PhoneCommandTexts.cantFindContact(who.name)))
        }
        val name = contact.names.first()
        val entry = when (val choice = ContactMatcher.number(contact, who.kind)) {
            is NumberChoice.One -> choice.entry
            is NumberChoice.Several -> {
                val kinds = choice.entries.map { it.kind }
                // The answer is a number or a kind, where "cell" counts as "mobile".
                val question = PhoneCommandTexts.whichNumber(kinds)
                when (val answer = askAmong(conversation, question, kinds.map(ContactMatcher::kindOf), ContactMatcher::kindOf)) {
                    is Answer.Chosen -> choice.entries[answer.index]
                    is Answer.Ends -> return Found.Ends(answer.result)
                }
            }
            NumberChoice.None -> {
                val missing = who.kind?.let { PhoneCommandTexts.noNumberOfKind(name, it) } ?: PhoneCommandTexts.noNumber(name)
                return Found.Ends(CommandResult(Outcome.FAILED, say = missing))
            }
        }
        return Found.Number(digitsOf(entry), PhoneCommandTexts.contactAndKind(name, entry.kind))
    }

    // The stored number as the phone app takes it, with its pause, wait or extension; a number that is no plain number,
    // such as one with letters, goes to the phone app as it is stored, and Kestrel never presses Call for it.
    private fun digitsOf(entry: PhoneEntry): String = PhoneNumbers.dialable(entry.number) ?: entry.number

    companion object {
        /** The countdown after the announcement (decision 1). */
        const val COUNTDOWN_MILLIS = 4_000L

        fun create(environment: CommandEnvironment) = CallCommands(
            ContactsReader(contactsAccess(environment.context), AndroidContacts(environment.context.contentResolver), environment.report),
            AndroidDialer(environment.context, environment.screen),
            environment.openSettings,
        )
    }
}

/**
 * The default phone app through Kestrel's accessibility service: `ACTION_DIAL` with a `tel:` link, which shows the number
 * (prototype P4), and its call button: on Google's phone app the view ID `dialpad_voice_call_button`, otherwise a button
 * named "Call", "Dial" or "Voice call" (Samsung's, not checked yet). Whether the call started comes from Android's call
 * state, through the call monitor that Kestrel's modes Ringing and In call use, which needs READ_PHONE_STATE.
 */
internal class AndroidDialer(private val context: Context, private val screen: () -> ScreenSession?) : Dialer {
    private val telecom: TelecomManager? = context.getSystemService(TelecomManager::class.java)
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)

    override fun rule(digits: String): DialRule = ruleFor(digits) { emergency(telephony, it) }

    override suspend fun open(digits: String): Boolean {
        val session = screen() ?: return false
        val phoneApp = telecom?.defaultDialerPackage
        // Google's phone app keeps its own number empty after a DIAL with the number that its field already shows, and its
        // call button then calls no one, so a field on the screen that shows the number is emptied first. One that is not on
        // the screen cannot be read; callStarted() then tells that the call did not start.
        if (phoneApp != null) emptyShownNumber(session, phoneApp, digits)
        // Uri.fromParts encodes "#", "*", "," and ";", which a parsed tel: link would cut off or change.
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", digits, null))
        phoneApp?.let { intent.setPackage(it) }
        return session.system.start(intent) is ActionResult.Done
    }

    // Empties the number fields of [phoneApp] that show [digits] now, through the actions of the field itself and of the
    // phone app's delete key, never through a gesture, which could land elsewhere once the screen has changed.
    private suspend fun emptyShownNumber(session: ScreenSession, phoneApp: String, digits: String) {
        val live = session.snapshots.current()
        val fields = numberFields(live.snapshot, setOf(phoneApp))
        if (fields.isEmpty()) return
        val deleteKey = deleteKey(live.snapshot, phoneApp, fields)
        emptyFields(
            fields,
            digits,
            read = { readFields(live, fields) },
            clear = { field -> setEmpty(live, field) },
            longDelete = { deleteKey != null && act(live, deleteKey, AccessibilityNodeInfo.ACTION_LONG_CLICK) },
            delete = { deleteKey != null && act(live, deleteKey, AccessibilityNodeInfo.ACTION_CLICK) },
        )
    }

    // The field's own set-text action with no text.
    private fun setEmpty(live: LiveSnapshot, field: ScreenElement): Boolean {
        val node = live.node(field.key) ?: return false
        if (!node.refresh() || !node.isEnabled || node.actionList.none { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }) return false
        val arguments = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "") }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    // The element's own [action], only while it is visible and enabled.
    private fun act(live: LiveSnapshot, element: ScreenElement, action: Int): Boolean {
        val node = live.node(element.key) ?: return false
        return node.refresh() && node.isVisibleToUser && node.isEnabled && node.performAction(action)
    }

    override suspend fun awaitCallButton(): Boolean {
        val session = screen() ?: return false
        val phoneApp = telecom?.defaultDialerPackage ?: return false
        return awaitCallButton(phoneApp) { session.snapshots.current().snapshot } != null
    }

    override suspend fun press(digits: String): Pressed {
        val session = screen() ?: return Pressed.REFUSED
        val phoneApp = telecom?.defaultDialerPackage ?: return Pressed.REFUSED
        val live = awaitButton(session, phoneApp)
            ?: return if (inCall(session.snapshots.current().snapshot)) Pressed.CALLING else Pressed.REFUSED
        val button = callButton(live.snapshot, phoneApp) ?: return Pressed.REFUSED
        // The phone app may keep digits that were typed before, so Kestrel presses Call only while the number field shows the
        // number that it announced, as the phone app itself reports it now, past Android's node cache.
        val fields = numberFields(live.snapshot, setOf(phoneApp))
        pressChecked(fields, digits, read = { readFields(live, fields) }) { click(live, button) }?.let { pressed ->
            return if (pressed == Pressed.REFUSED && inCall(session.snapshots.current().snapshot)) Pressed.CALLING else pressed
        }
        val chooser = withTimeoutOrNull(SIM_MILLIS) {
            while (!simChooser(session.snapshots.current().snapshot)) delay(POLL_MILLIS)
            true
        }
        return if (chooser == true) Pressed.CHOOSE_SIM else Pressed.CALLING
    }

    override suspend fun callStarted(): Boolean? {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return null
        return awaitCall(AndroidCallMonitor(context))
    }

    // The snapshot that shows the phone app's call button again after the countdown, with the live nodes behind it.
    private suspend fun awaitButton(session: ScreenSession, phoneApp: String): LiveSnapshot? = withTimeoutOrNull(AGAIN_MILLIS) {
        var found: LiveSnapshot? = null
        while (found == null) {
            val live = session.snapshots.current()
            if (callButton(live.snapshot, phoneApp) != null) found = live else delay(POLL_MILLIS)
        }
        found
    }

    // The button's own click action, as Google's call button and the named buttons that callButton() finds take it; a
    // gesture's fallback could reach another control once the screen has changed.
    private fun click(live: LiveSnapshot, button: ScreenElement): Boolean {
        val node = live.node(button.key) ?: return false
        return node.refresh() && node.isVisibleToUser && node.isEnabled && node.isClickable &&
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // The phone app's in-call screen shows an "End call" control.
    private fun inCall(snapshot: ScreenSnapshot): Boolean {
        val phoneApp = telecom?.defaultDialerPackage
        return snapshot.elements.any { it.packageName == phoneApp && namesOf(snapshot, it).any { name -> name == "end call" } }
    }

    // A window of the phone app or of Android's call service that offers the SIM cards.
    private fun simChooser(snapshot: ScreenSnapshot): Boolean {
        val phoneApp = telecom?.defaultDialerPackage
        return snapshot.elements.any { element ->
            (element.packageName == phoneApp || element.packageName == TELECOM) &&
                namesOf(snapshot, element).any { "sim" in it.split(' ') }
        }
    }

    companion object {
        /** How long the phone app may take to show, as on its cold start, which takes about 4 s on kestrel-api37. */
        private const val APP_MILLIS = 10_000L
        private const val BUTTON_MILLIS = 3_000L
        private const val AGAIN_MILLIS = 1_000L
        private const val SIM_MILLIS = 1_500L
        private const val POLL_MILLIS = 100L

        /** How long a call may take to start after the press (the controller's decision on Task 6's review). */
        private const val START_MILLIS = 5_000L

        // Presses of the delete key beyond the field's length, for a phone app that removes a separator with its own press.
        private const val EXTRA_DELETES = 2
        private const val GOOGLE_CALL_BUTTON = ":id/dialpad_voice_call_button"
        private const val DIGITS = ":id/digits"
        private const val TELECOM = "com.android.server.telecom"
        private val CALL_NAMES = setOf("call", "dial", "voice call")

        /**
         * The call button of [phoneApp] in [snapshot]: Google's by its view ID, and only when no element has that ID, a
         * clickable element named "Call", "Dial" or "Voice call" (design D11).
         */
        fun callButton(snapshot: ScreenSnapshot, phoneApp: String): ScreenElement? {
            val own = snapshot.elements.filter { it.enabled && it.packageName == phoneApp }
            return own.firstOrNull { it.viewId?.endsWith(GOOGLE_CALL_BUTTON) == true }
                ?: own.firstOrNull { it.clickable && namesOf(snapshot, it).any { name -> name in CALL_NAMES } }
        }

        /**
         * Waits for [phoneApp]'s call button in the snapshots that [read] gives: up to [APP_MILLIS] until the phone app shows
         * an element, as after its cold start, and from then up to 3 s for the button (design D11). Null when neither shows
         * in time.
         */
        suspend fun awaitCallButton(phoneApp: String, read: suspend () -> ScreenSnapshot): ScreenElement? {
            withTimeoutOrNull(APP_MILLIS) {
                while (read().elements.none { it.packageName == phoneApp }) delay(POLL_MILLIS)
            } ?: return null
            return withTimeoutOrNull(BUTTON_MILLIS) {
                var found: ScreenElement? = null
                while (found == null) {
                    found = callButton(read(), phoneApp)
                    if (found == null) delay(POLL_MILLIS)
                }
                found
            }
        }

        /**
         * Presses Call with [click] only while [fields], the phone app's number fields, show exactly [digits], the number
         * that Kestrel announced: [read] gives what each field's key shows now, as the phone app itself reports it, and
         * leaves out a field that it cannot read. It reads right before the decision and again right before the click,
         * and a cancel that came during the reads stops it before the click. Null once [click] went through; otherwise how
         * the press ended.
         */
        suspend fun pressChecked(
            fields: List<ScreenElement>,
            digits: String,
            read: suspend () -> Map<String, String?>,
            click: suspend () -> Boolean,
        ): Pressed? {
            fieldCheck(fields, read(), digits)?.let { return it }
            // The phone app may change the number until the click, as after another DIAL or the owner's typing.
            fieldCheck(fields, read(), digits)?.let { return it }
            // The reads are blocking binder calls, which a "stop" does not interrupt.
            currentCoroutineContext().ensureActive()
            return if (click()) null else Pressed.REFUSED
        }

        /**
         * Empties those of [fields], the phone app's number fields, that show [digits] now, before a DIAL with [digits]
         * (the controller's decision on Task 6's review): Google's phone app keeps its own number empty after a DIAL with
         * the number that its field already shows, and its call button then calls no one. [read] gives what each field
         * shows now, as the phone app itself reports it, and leaves out a field that it cannot read, which stays as it is.
         * Kestrel tries [clear], the field's own set-text action; then [longDelete], a long press on the phone app's delete
         * key, which empties Google's field; and then [delete], one press of that key for each character left. It reads the
         * fields again after each step and stops once they are empty. True when no field shows [digits] any more.
         */
        suspend fun emptyFields(
            fields: List<ScreenElement>,
            digits: String,
            read: suspend () -> Map<String, String?>,
            clear: suspend (ScreenElement) -> Boolean,
            longDelete: suspend () -> Boolean,
            delete: suspend () -> Boolean,
        ): Boolean {
            var fresh = read()
            val showing = fields.filter { field -> fresh[field.key]?.let(PhoneNumbers::dialable) == digits }
            if (showing.isEmpty()) return true
            fun empty() = showing.all { it.key in fresh && fresh[it.key].isNullOrBlank() }
            showing.forEach { clear(it) }
            fresh = read()
            if (empty()) return true
            if (longDelete()) {
                fresh = read()
                if (empty()) return true
            }
            var presses = showing.maxOf { fresh[it.key]?.length ?: 0 } + EXTRA_DELETES
            while (presses-- > 0 && delete()) {
                fresh = read()
                if (empty()) return true
            }
            return showing.none { fresh[it.key]?.let(PhoneNumbers::dialable) == digits }
        }

        /**
         * Whether [calls] reports an active call within 5 s, as Android's call state shows it for Kestrel's mode In call:
         * the state at the start counts, and so does a change. The 5 s start when [press] has returned, after its wait of
         * up to 1.5 s for a SIM chooser. Null when [calls] cannot start, as after the owner took
         * READ_PHONE_STATE back. [calls] stops again in any case.
         */
        suspend fun awaitCall(calls: CallMonitor): Boolean? {
            val active = CompletableDeferred<Unit>()
            try {
                // The monitor reports on a thread of Android's.
                calls.start { state -> if (state == CallState.ACTIVE) active.complete(Unit) }
            } catch (e: RuntimeException) {
                return null
            }
            return try {
                withTimeoutOrNull(START_MILLIS) { active.await() } != null
            } finally {
                calls.stop()
            }
        }

        /**
         * Whether [fields] show exactly [digits] in [fresh], their texts now by key: null when they do; REFUSED when a field
         * could not be read or none shows a number, because Kestrel cannot check the number then; OTHER_NUMBER for any other
         * number, also one that differs only in its "+", "*", "#", pause or wait, while the phone app's spaces and dashes
         * may differ.
         */
        fun fieldCheck(fields: List<ScreenElement>, fresh: Map<String, String?>, digits: String): Pressed? {
            if (fields.isEmpty() || fields.any { it.key !in fresh }) return Pressed.REFUSED
            val shown = fields.mapNotNull { field -> fresh[field.key]?.takeIf { showsNumber(field, it) } }
            if (shown.isEmpty()) return Pressed.REFUSED
            return if (shown.all { PhoneNumbers.dialable(it) == digits }) null else Pressed.OTHER_NUMBER
        }

        /**
         * Whether [element] is a phone app's number field: a text field, or a field with the view ID `digits`, which
         * Android's emergency dialer does not make editable.
         */
        fun isNumberField(element: ScreenElement): Boolean =
            element.editable && !element.password || element.viewId?.endsWith(DIGITS) == true

        /** The number fields of the [phoneApps] in [snapshot], also those that show nothing yet. */
        fun numberFields(snapshot: ScreenSnapshot, phoneApps: Set<String>): List<ScreenElement> =
            snapshot.elements.filter { it.packageName in phoneApps && isNumberField(it) }

        /**
         * The enabled delete key of [phoneApp] in the window of one of [fields], its number fields, so that a "Delete" of
         * another window of the phone app, such as a call-history dialog, never gets the presses.
         */
        fun deleteKey(snapshot: ScreenSnapshot, phoneApp: String, fields: List<ScreenElement>): ScreenElement? {
            val windows = fields.mapTo(HashSet()) { it.windowId }
            return snapshot.elements.firstOrNull {
                it.packageName == phoneApp && it.windowId in windows && it.enabled && EmergencyGuard.isDeleteKey(it)
            }
        }

        /**
         * Whether the number field [element] shows [text] as a number: the field with the view ID `digits` with any text,
         * and another text field only with a digit, `*` or `#`, so that a name in the phone app's search box counts as none.
         */
        fun showsNumber(element: ScreenElement, text: String?): Boolean {
            if (text.isNullOrBlank() || !isNumberField(element)) return false
            return element.viewId?.endsWith(DIGITS) == true || text.any { it.isDigit() || it == '*' || it == '#' }
        }

        /** The texts of [phoneApp]'s number fields that are not empty; the number field is the first. */
        fun fieldTexts(snapshot: ScreenSnapshot, phoneApp: String): List<String> = snapshot.elements
            .filter { it.packageName == phoneApp && isNumberField(it) && !it.text.isNullOrBlank() }
            .mapNotNull { it.text }

        /**
         * Whether Android reports [digits] as an emergency number, or null when Kestrel cannot tell: without a
         * TelephonyManager, or when the check throws, as it can on a phone without telephony.
         */
        fun emergency(telephony: TelephonyManager?, digits: String): Boolean? = try {
            telephony?.isEmergencyNumber(digits)
        } catch (e: RuntimeException) {
            null
        }

        /**
         * Whether Kestrel may press Call for [digits] itself (design D11): a long number without "*" or "#" that Android
         * reports as no emergency number; when Kestrel cannot tell ([emergency] null), it does not.
         */
        fun mayCall(digits: String, emergency: Boolean?): Boolean = PhoneNumbers.mayAutoDial(digits) && emergency == false

        /**
         * What "call X" does with [digits]; [emergency] is Android's check of the main number, null when it cannot tell.
         * A code with `*` or `#`, or a stored number with letters, never; an emergency number, also when Android cannot
         * tell, after "yes, call"; a number with fewer than 5 digits never; any other after the countdown.
         */
        fun ruleFor(digits: String, emergency: (String) -> Boolean?): DialRule = when {
            !digits.all { it.isDigit() || it == '+' || it == ',' || it == ';' } -> DialRule.TAP_YOURSELF
            emergency(PhoneNumbers.mainNumber(digits)) != false -> DialRule.EMERGENCY
            !PhoneNumbers.mayAutoDial(digits) -> DialRule.TAP_YOURSELF
            else -> DialRule.CALL
        }

        private fun namesOf(snapshot: ScreenSnapshot, element: ScreenElement): List<String> =
            (ScreenTargets.names(snapshot, element) + listOfNotNull(element.contentDescription)).map { Normalizer.normalize(it) }
    }
}

/**
 * The texts that [fields] show now, by key, as their app itself reports them: `refresh()` asks the app past Android's node
 * cache, which an event that never came can leave stale, and does not write that cache. A field that Kestrel cannot read
 * again, such as one that has gone, is left out. It makes a blocking binder call for each field.
 */
internal fun readFields(live: LiveSnapshot, fields: List<ScreenElement>): Map<String, String?> {
    val texts = HashMap<String, String?>()
    for (field in fields) {
        val node = live.node(field.key) ?: continue
        if (!node.refresh()) continue
        // As in the snapshot: no text for a password field, nor for a field that shows its hint.
        texts[field.key] = if (node.isPassword || node.isShowingHintText) null else Labels.clean(node.text)
    }
    return texts
}

/**
 * What Kestrel does with a command by voice that may call an emergency number (the owner's decision, decision 13 of
 * sub-project 3): [CONFIRM] asks for "yes, call" first, and [TOUCH_ONLY] refuses, so that the owner taps.
 */
internal enum class EmergencyPolicy {
    CONFIRM,
    TOUCH_ONLY,
    ;

    companion object {
        /** The one constant that switches the policy; the owner chose [CONFIRM]. */
        val CURRENT: EmergencyPolicy = CONFIRM
    }
}

/**
 * Keeps commands by voice from calling an emergency number without the owner's "yes, call" (design spec, section 8.7),
 * with two rules that need no list of buttons. R1: an element whose name (text, content description or view ID, and the
 * label of an element that takes a touch) holds the word "emergency" or "sos", or whose nearest clickable parent's does,
 * such as the emergency buttons of the power menu and the lock screen. R2: every window of a phone package while one of
 * its windows shows an element that R1 names (an emergency dialer), an element whose own text or content description is
 * as a whole an emergency number (a call-history row or a search result "112"), or a number field with a number that
 * Kestrel would not call itself (an emergency number, also when Android cannot tell, a number with fewer than 5 digits or
 * with `*` or `#`, or a text that reads as no number): every action on their elements, except a tap on a digit key and the
 * actions on the number field and its delete key, and every gesture that starts or ends inside them. A window of a phone
 * package in which Kestrel finds no number field to read, also one that shows no element yet, needs the yes for every
 * action that can reach into it, on any of its elements too. After a "yes, call", the command must still find the same
 * elements in the same windows, or it ends. R2 reads the phone apps' number fields as the apps themselves report them
 * now, past
 * Android's node cache, right before the decision and, while a window of a phone package is on the screen, again right
 * before the guard lets the command go on; a field that it cannot read again counts as one whose number Kestrel would not
 * call. The screen part then acts on its own snapshot. The phone
 * part asks it before the screen part sees a command. Every variant of the sealed command model is listed, without an
 * `else`, so that the compiler names a new one; each target is found with the screen part's own search, so that a number
 * names an element whatever the command.
 */
internal class EmergencyGuard(
    context: Context,
    private val targets: ScreenTargets,
    private val screen: () -> ScreenSession?,
    private val policy: EmergencyPolicy = EmergencyPolicy.CURRENT,
) {
    private val telecom: TelecomManager? = context.getSystemService(TelecomManager::class.java)
    private val telephony: TelephonyManager? = context.getSystemService(TelephonyManager::class.java)

    /**
     * Null when [command] may run; otherwise how it ends: cancelled without the owner's "yes, call", or refused under
     * [EmergencyPolicy.TOUCH_ONLY]. A text command reaches the phone part only when the text part declined it, and the
     * executor then runs [heard] as a bare label through the screen part, so that bare text counts too.
     */
    suspend fun check(command: Command, heard: String, conversation: CommandConversation): CommandResult? {
        val session = screen() ?: return null
        val bare = if (command is TextCommand) reaches(Command.Bare.of(heard), session.screenBounds) else emptyList()
        val reaches = reaches(command, session.screenBounds) + bare
        if (reaches.isEmpty()) return null
        val phoneApps = PHONE_PACKAGES + listOfNotNull(telecom?.defaultDialerPackage)
        val view = askedOn(reaches, phoneApps, { AndroidDialer.emergency(telephony, it) }) { freshView(session, phoneApps) }
            ?: return null
        val asked = resolved(reaches, view)
        confirm(policy, conversation)?.let { return it }
        // The yes counts only for what the question was about: a number that names another element by now, or a screen
        // that changed under the question, ends the command.
        val now = targets.view() ?: return CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
        return if (resolved(reaches, now) == asked) null else CommandResult(Outcome.FAILED, say = CommandTexts.SCREEN_CHANGED)
    }

    // The current screen, with the texts of the phone apps' number fields as the apps themselves report them now.
    private suspend fun freshView(session: ScreenSession, phoneApps: Set<String>): FreshView {
        val live = session.snapshots.current()
        val texts = readFields(live, AndroidDialer.numberFields(live.snapshot, phoneApps))
        // The overlays change only on the main thread.
        val view = withContext(Dispatchers.Main) { ScreenView(live.snapshot, session.numberBadges, session.grid) }
        return fresh(view, phoneApps, texts)
    }

    /**
     * A view of the screen whose phone number fields show what a fresh read found, and the keys of the fields that the read
     * could not reach, which R2 counts as fields that show a number that Kestrel would not call.
     */
    class FreshView(val view: ScreenView, val unreadable: Set<String>)

    /** Where a command acts: an element that it names, found as the screen part finds it, or a point of a gesture. */
    sealed interface Reach {
        /**
         * [gesture]: a swipe, a pinch or a drag on the element, which R2's exceptions do not cover; [longPress]: a long
         * press, which R2's exception for the digit keys does not cover, because a long press on 1 calls the voicemail and
         * one on 0 types "+"; [swipe]: a swipe that starts on the element, whose end counts too.
         */
        data class Element(
            val target: Target?,
            val kind: TargetKind,
            val strictness: Strictness,
            val cells: Boolean,
            val gesture: Boolean = false,
            val longPress: Boolean = false,
            val swipe: Swipe? = null,
        ) : Reach

        data class Point(val x: Int, val y: Int) : Reach

        /** The end of a drag that the owner gives after "Drag it where?", which can lie anywhere. */
        data object Anywhere : Reach
    }

    /** A swipe in the finger's [direction] on a screen of [screen] bounds, which ends a third of the screen away. */
    data class Swipe(val direction: ScrollDirection, val screen: IntRect)

    companion object {
        /** The phone apps and Android's call services; the default phone app counts too. */
        val PHONE_PACKAGES = setOf(
            "com.android.phone", "com.android.dialer", "com.google.android.dialer", "com.samsung.android.dialer",
            "com.samsung.android.incallui", "com.android.server.telecom",
            // One UI's emergency dialer.
            "com.samsung.android.app.telephonyui",
        )
        private val EMERGENCY_WORDS = setOf("emergency", "sos")
        private val KEY_IDS = setOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "star", "pound")
        private val DELETE_IDS = setOf("deletebutton", "delete_button", "backspace")
        private val DELETE_NAMES = setOf("delete", "backspace", "erase")
        private val KEY_NAMES = setOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "#", "+")
        private const val KESTREL = "dev.kestrel"
        private const val NAME = "kestrel"

        // Right before the decision, and while a phone app is on the screen once more right before the guard lets the command
        // go on (the controller's decisions on Task 6 and its review).
        private const val READS = 2

        /** Under [EmergencyPolicy.CONFIRM], asks for "yes, call": null when the owner said it; otherwise how the command ends. */
        suspend fun confirm(policy: EmergencyPolicy, conversation: CommandConversation): CommandResult? = when (policy) {
            EmergencyPolicy.TOUCH_ONLY -> CommandResult(Outcome.FAILED, say = PhoneCommandTexts.EMERGENCY_TOUCH_ONLY)
            // Another answer, the question's timer (null), a touch (null) or a cancel ends the command as cancelled.
            EmergencyPolicy.CONFIRM ->
                if (confirmed(conversation.ask(PhoneCommandTexts.EMERGENCY_QUESTION))) null else CommandResult(Outcome.CANCELLED)
        }

        /**
         * Whether [answer] is exactly "yes, call", with or without "Kestrel" after it; an answer that starts with the name
         * is a request of Kestrel's own, which ends the command before it gets here (plan 3A's rules).
         */
        fun confirmed(answer: String?): Boolean {
            val words = Normalizer.words(answer ?: return false).toMutableList()
            if (words.lastOrNull() == NAME) words.removeAt(words.lastIndex)
            return words == listOf("yes", "call")
        }

        /** R2's packages: [PHONE_PACKAGES] and the default phone app. */
        fun phoneApps(context: Context): Set<String> =
            PHONE_PACKAGES + listOfNotNull(context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage)

        /**
         * The view on which [reaches] need the owner's "yes, call", or null when they need none. [read] gives the screen
         * with the phone apps' number fields as the apps themselves report them now, past Android's node cache; it reads
         * right before the decision and, when that needs no yes while a window of [phoneApps] is on the screen, once more
         * right before the guard lets the command go on, so that a number that the phone app shows by then counts too.
         */
        suspend fun askedOn(
            reaches: List<Reach>,
            phoneApps: Set<String>,
            emergency: (String) -> Boolean?,
            read: suspend () -> FreshView?,
        ): ScreenView? {
            repeat(READS) {
                val fresh = read() ?: return null
                if (needsYes(reaches, fresh.view, phoneApps, emergency, fresh.unreadable)) return fresh.view
                // Each read waits for a quiet screen, which costs the command time, so without a phone app there is no other.
                if (!showsPhoneApp(fresh.view.snapshot, phoneApps)) return null
            }
            return null
        }

        // Whether a window or an element of [phoneApps] is on the screen.
        private fun showsPhoneApp(snapshot: ScreenSnapshot, phoneApps: Set<String>): Boolean =
            snapshot.windows.any { it.packageName in phoneApps } || snapshot.elements.any { it.packageName in phoneApps }

        /**
         * [view] with the texts that a fresh read found in the number fields of [phoneApps] ([texts], by key); a field that
         * the read could not reach is unreadable.
         */
        fun fresh(view: ScreenView, phoneApps: Set<String>, texts: Map<String, String?>): FreshView {
            val fields = AndroidDialer.numberFields(view.snapshot, phoneApps).mapTo(HashSet()) { it.key }
            val elements = view.snapshot.elements.map { if (it.key in texts) it.copy(text = texts[it.key]) else it }
            return FreshView(view.copy(snapshot = view.snapshot.copy(elements = elements)), fields - texts.keys)
        }

        /**
         * Whether "search for [query]" types a number into a phone app (the controller's decision on Task 6): Kestrel
         * refuses a query with any digit in [snapshot]'s app window in front, or in [target], the search field or button
         * that it found, when either belongs to [phoneApps], because a number there could start a call. A name is searched
         * for.
         */
        fun refusesSearch(query: String, snapshot: ScreenSnapshot, target: ScreenElement?, phoneApps: Set<String>): Boolean {
            if (query.none(Char::isDigit)) return false
            val front = snapshot.windows.firstOrNull { it.kind == WindowKind.APPLICATION && it.active }?.packageName
            return front in phoneApps || target?.packageName in phoneApps
        }

        /**
         * Whether one of [reaches] needs the owner's "yes, call" in [view] under R1 or R2; [phoneApps] are R2's packages,
         * [emergency] is Android's check, null when it cannot tell, and [unreadable] are the keys of number fields that
         * Kestrel could not read again, which ask as a number that it would not call does. In a window of [phoneApps] where
         * Kestrel finds no number field ([blindWindows]), every reach into it asks.
         */
        fun needsYes(
            reaches: List<Reach>,
            view: ScreenView,
            phoneApps: Set<String>,
            emergency: (String) -> Boolean?,
            unreadable: Set<String> = emptySet(),
        ): Boolean {
            val snapshot = view.snapshot
            val named = snapshot.elements.indices.filterTo(HashSet()) { emergencyNamed(snapshot, it) }
            val windows = guardedWindows(snapshot, phoneApps, emergency, unreadable)
            val blind = blindWindows(snapshot, phoneApps)
            if (named.isEmpty() && windows.isEmpty() && blind.isEmpty()) return false
            val windowBounds = snapshot.windows.filter { it.id in windows || it.id in blind }.map { it.bounds }
            // A tap reaches what lies under the point, also a button that holds a caption outside its view tree.
            val namedBounds = named.map { snapshot.elements[it] }.filter { it.isActionable }.map { it.bounds }
            fun point(x: Int, y: Int) = (windowBounds + namedBounds).any { it.holds(x, y) }
            // Where a swipe that starts at ([x], [y]) ends.
            fun swipeEnd(reach: Reach.Element, x: Int, y: Int): Boolean {
                val swipe = reach.swipe ?: return false
                val line = GesturePaths.limit(swipe.screen, x, y, swipe.direction) ?: return false
                return point(line.toX, line.toY)
            }
            fun element(key: String, reach: Reach.Element): Boolean {
                val index = snapshot.elements.indexOfFirst { it.key == key }
                if (index < 0) return false
                val found = snapshot.elements[index]
                val centreX = found.bounds.centerX
                val centreY = found.bounds.centerY
                if (index in named || touchParent(snapshot, index) in named || namedBounds.any { it.holds(centreX, centreY) }) return true
                if (found.windowId in blind) return true
                // A swipe starts where a fallback tap would go.
                val start = ScreenTargets.gesturePoint(snapshot, found)
                if (swipeEnd(reach, start?.x ?: centreX, start?.y ?: centreY)) return true
                if (reach.gesture) return found.windowId in windows || windowBounds.any { it.holds(centreX, centreY) }
                val tap = (reach.kind == TargetKind.TAP || reach.kind == TargetKind.BARE) && !reach.longPress
                return found.windowId in windows && !typesTheNumber(snapshot, actor(snapshot, index), tap)
            }
            return reaches.any { reach ->
                when (reach) {
                    is Reach.Point -> point(reach.x, reach.y)
                    Reach.Anywhere -> true
                    is Reach.Element -> when (val found = ScreenTargets.decide(view, reach.target, reach.kind, reach.strictness, reach.cells)) {
                        is Resolution.One -> element(found.element.key, reach)
                        is Resolution.Several -> found.elements.any { element(it.key, reach) }
                        is Resolution.Cell -> found.bounds.let { point(it.centerX, it.centerY) || swipeEnd(reach, it.centerX, it.centerY) }
                        Resolution.NotFound, Resolution.NeedNumbers, is Resolution.NoSuchNumber, Resolution.Changed,
                        Resolution.NoScreen -> false
                    }
                }
            }
        }

        /** R1: whether the name of element [index] holds "emergency" or "sos"; never Kestrel's own windows, such as its bar. */
        fun emergencyNamed(snapshot: ScreenSnapshot, index: Int): Boolean {
            val element = snapshot.elements[index]
            if (element.packageName == KESTREL) return false
            // A view ID such as "emergencyButton" or "sos_call" splits into words.
            val id = element.viewId?.substringAfter(":id/")?.replace(Regex("([a-z])([A-Z])"), "$1 $2")
            val names = listOfNotNull(element.text, element.contentDescription, id, element.label.takeIf { element.isActionable })
            return names.any { name -> Normalizer.words(name).any { it in EMERGENCY_WORDS } }
        }

        /**
         * R2: the IDs of the windows of the phone packages, among [phoneApps], that show an element or a title that R1
         * names (an emergency dialer, also one whose number field Kestrel cannot read), a number field with a number that
         * Kestrel would not call itself, a number field whose key is among [unreadable], because Kestrel could not read it
         * again, or an element that shows an emergency number as a whole ([showsEmergencyNumber]), such as a call-history
         * row or a search result "112"; every window of such a package counts, so that its menus and dialogs count too.
         */
        fun guardedWindows(
            snapshot: ScreenSnapshot,
            phoneApps: Set<String>,
            emergency: (String) -> Boolean?,
            unreadable: Set<String> = emptySet(),
        ): Set<Int> {
            val fields = snapshot.elements.filter { it.packageName in phoneApps }.filter { field ->
                if (field.key in unreadable) return@filter true
                if (!AndroidDialer.showsNumber(field, field.text)) return@filter false
                val shown = PhoneNumbers.dialable(field.text!!) ?: return@filter true
                !AndroidDialer.mayCall(shown, emergency(PhoneNumbers.mainNumber(shown)))
            }
            val named = snapshot.elements.indices.filter { snapshot.elements[it].packageName in phoneApps && emergencyNamed(snapshot, it) }
                .map { snapshot.elements[it] }
            val shown = snapshot.elements.filter { it.packageName in phoneApps && showsEmergencyNumber(it, emergency) }
            val packages = (fields + named + shown).mapNotNullTo(HashSet()) { it.packageName }
            // A window's title, such as an emergency dialer's "Emergency call", names it too.
            snapshot.windows
                .filter { window -> window.packageName in phoneApps && Normalizer.words(window.title.orEmpty()).any { it in EMERGENCY_WORDS } }
                .mapNotNullTo(packages) { it.packageName }
            return snapshot.elements.filter { it.packageName in packages }.mapTo(HashSet()) { it.windowId } +
                snapshot.windows.filter { it.packageName in packages }.map { it.id }
        }

        /**
         * Whether [element]'s own text or content description, trimmed, is as a whole a number that a phone app dials
         * ([PhoneNumbers.dialable], as for the number field) and that Android reports as an emergency number, or cannot
         * check ([emergency] not false). A text with other characters is none: a time such as "08:15", which Android
         * counts as the emergency number 08 without a SIM card, a date such as "17 Nov", or a name with a number in it.
         */
        fun showsEmergencyNumber(element: ScreenElement, emergency: (String) -> Boolean?): Boolean =
            listOfNotNull(element.text, element.contentDescription).any { text ->
                val digits = PhoneNumbers.dialable(text.trim()) ?: return@any false
                emergency(PhoneNumbers.mainNumber(digits)) != false
            }

        /**
         * The IDs of the windows of [phoneApps] in which Kestrel finds no number field to read (the controller's decision on
         * Task 6's review, failing closed): none at all, as in a phone app's list of calls, a field that is no text field and
         * has not the view ID `digits`, or no element yet, as while the window starts. Kestrel cannot tell then which number
         * an action there may call, so every action that can reach into these windows needs the yes, on any of their
         * elements too.
         */
        fun blindWindows(snapshot: ScreenSnapshot, phoneApps: Set<String>): Set<Int> {
            val phoneWindows = snapshot.windows.filter { it.packageName in phoneApps }.mapTo(HashSet()) { it.id } +
                snapshot.elements.filter { it.packageName in phoneApps }.map { it.windowId }
            return phoneWindows - AndroidDialer.numberFields(snapshot, phoneApps).mapTo(HashSet()) { it.windowId }
        }

        /**
         * What [reaches] find in [view]: the keys and windows of the elements, a grid square, or the point itself, so that
         * the yes counts only while the command still finds the same.
         */
        fun resolved(reaches: List<Reach>, view: ScreenView): List<Any> = reaches.map { reach ->
            when (reach) {
                is Reach.Element -> when (val found = ScreenTargets.decide(view, reach.target, reach.kind, reach.strictness, reach.cells)) {
                    is Resolution.One -> place(view, found.element.key)
                    is Resolution.Several -> found.elements.map { place(view, it.key) }
                    is Resolution.Cell -> found.bounds
                    Resolution.NotFound, Resolution.NeedNumbers, is Resolution.NoSuchNumber, Resolution.Changed,
                    Resolution.NoScreen -> found
                }
                is Reach.Point, Reach.Anywhere -> reach
            }
        }

        private fun place(view: ScreenView, key: String) = key to view.snapshot.element(key)?.windowId

        // What a tap on element [index] clicks: the element when it takes a touch, or else its nearest clickable parent.
        private fun actor(snapshot: ScreenSnapshot, index: Int): Int =
            if (snapshot.elements[index].isActionable) index else touchParent(snapshot, index) ?: index

        // The nearest parent of element [index] that takes a touch.
        private fun touchParent(snapshot: ScreenSnapshot, index: Int): Int? {
            var parent = snapshot.elements[index].parent
            while (parent != null) {
                val ancestor = snapshot.elements.getOrNull(parent) ?: return null
                if (ancestor.clickable || ancestor.longClickable) return parent
                parent = ancestor.parent
            }
            return null
        }

        // R2's exceptions, so that the owner can type and correct the number by voice: the number field and its delete key,
        // and for a [tap] only, the digit keys (by their view IDs, or a whole name that is one digit, "*", "#" or "+", as a
        // dial pad's keys have, never "1 Telenor"); a long press on a digit key can call, as on 1 the voicemail, or type "+".
        private fun typesTheNumber(snapshot: ScreenSnapshot, index: Int, tap: Boolean): Boolean {
            val element = snapshot.elements[index]
            if (AndroidDialer.isNumberField(element) || isDeleteKey(element)) return true
            if (!tap) return false
            if (element.viewId?.substringAfter(":id/")?.lowercase() in KEY_IDS) return true
            if (!element.clickable && !element.longClickable) return false
            return listOfNotNull(element.text, element.contentDescription).any { it.trim() in KEY_NAMES }
        }

        /**
         * Whether [element] is a phone app's delete key: by its view ID, or, when it takes a touch, by a whole name such as
         * "Delete" or "Backspace".
         */
        fun isDeleteKey(element: ScreenElement): Boolean {
            if (element.viewId?.substringAfter(":id/")?.lowercase() in DELETE_IDS) return true
            if (!element.clickable && !element.longClickable) return false
            return listOfNotNull(element.text, element.contentDescription).any { Normalizer.normalize(it) in DELETE_NAMES }
        }

        private fun IntRect.holds(x: Int, y: Int) = x in left until right && y in top until bottom

        /** Where [command] acts on a screen of [screen] bounds; empty for a command that acts on no element or point. */
        fun reaches(command: Command, screen: IntRect): List<Reach> {
            val centre = listOf(Reach.Point(screen.centerX, screen.centerY))
            fun tap(target: Target) = listOf(Reach.Element(target, TargetKind.TAP, Strictness.NORMAL, cells = true))
            fun gesture(target: Target) = listOf(Reach.Element(target, TargetKind.TAP, Strictness.NORMAL, cells = true, gesture = true))
            fun element(target: Target?, kind: TargetKind) =
                target?.let { listOf(Reach.Element(it, kind, Strictness.NORMAL, cells = false)) }.orEmpty()
            return when (command) {
                is Command.Tap -> tap(command.target)
                is Command.LongPress -> listOf(Reach.Element(command.target, TargetKind.TAP, Strictness.NORMAL, cells = true, longPress = true))
                is Command.Bare -> listOf(
                    command.number?.let { Reach.Element(Target.Number(it), TargetKind.BARE, Strictness.NORMAL, cells = true) }
                        ?: Reach.Element(Target.Label(command.text), TargetKind.BARE, Strictness.STRICT, cells = false),
                )
                // Without a target, these act on what the screen part's search finds without one, which this search finds too.
                is Command.Check -> listOf(Reach.Element(command.target, TargetKind.CHECK, Strictness.NORMAL, cells = false))
                is Command.Switch -> listOf(Reach.Element(command.target, TargetKind.CHECK, Strictness.NORMAL, cells = false))
                is Command.Expand -> listOf(Reach.Element(command.target, TargetKind.EXPAND, Strictness.NORMAL, cells = false))
                is Command.Collapse -> listOf(Reach.Element(command.target, TargetKind.COLLAPSE, Strictness.NORMAL, cells = false))
                is Command.Slider -> listOf(Reach.Element(command.target, TargetKind.SLIDER, Strictness.NORMAL, cells = false))
                is Command.Dismiss -> element(command.target, TargetKind.DISMISS)
                is Command.Scroll -> command.target?.let { element(it, TargetKind.SCROLL) } ?: centre
                is Command.ScrollToEnd -> command.target?.let { element(it, TargetKind.SCROLL) } ?: centre
                is Command.SwipePage -> centre
                is Command.SwipeFromEdge -> GesturePaths.fromEdge(screen, screenEdge(command.edge)).let {
                    listOf(Reach.Point(it.fromX, it.fromY), Reach.Point(it.toX, it.toY))
                }
                // A swipe on an element counts where it starts and where it ends.
                is Command.Swipe -> command.target?.let {
                    val swipe = Swipe(scrollDirection(command.direction), screen)
                    listOf(Reach.Element(it, TargetKind.TAP, Strictness.NORMAL, cells = true, gesture = true, swipe = swipe))
                }
                    ?: GesturePaths.limit(screen, screen.centerX, screen.centerY, scrollDirection(command.direction))
                        ?.let { listOf(Reach.Point(it.fromX, it.fromY), Reach.Point(it.toX, it.toY)) }.orEmpty()
                is Command.Pinch -> command.target?.let { gesture(it) } ?: centre
                // Both ends; an end that the owner gives later can lie anywhere.
                is Command.Drag -> gesture(command.from) + (command.to?.let { gesture(it) } ?: listOf(Reach.Anywhere))
                // They only say what an element is or allows.
                is Command.WhatIs, is Command.CommandsFor -> emptyList()
                Command.ShowNumbers, Command.HideNumbers, Command.ShowLabels, Command.HideLabels, Command.ShowGrid,
                Command.HideGrid, Command.MoreSquares, Command.FewerSquares, Command.Again, is Command.Help -> emptyList()
                is TextCommand -> textReaches(command)
                is PhoneCommand -> phoneReaches(command)
            }
        }

        // The text part runs first and takes these in a focused field (plan 3B); only "start editing" names an element. The
        // text part also takes "search for" and refuses a number in a phone app's window itself (refusesSearch), so the
        // branch for it applies only if the text part ever declines it, and stays as a defence: "search for" taps a search
        // button and types into a search field, which in a phone package's window may be its number field, so it may act
        // anywhere.
        private fun textReaches(command: TextCommand): List<Reach> = when (command) {
            is TextCommand.StartEditing ->
                command.target?.let { listOf(Reach.Element(it, TargetKind.EDIT, Strictness.NORMAL, cells = false)) }.orEmpty()
            is TextCommand.Search -> listOf(Reach.Anywhere)
            TextCommand.StopEditing, is TextCommand.Type, TextCommand.Undo, TextCommand.Redo, is TextCommand.Insert,
            is TextCommand.Replace, is TextCommand.ReplaceBetween, is TextCommand.ChangeCase, TextCommand.FormatEmail,
            is TextCommand.Delete, is TextCommand.MoveCursor, is TextCommand.Select, TextCommand.Unselect,
            is TextCommand.Clipboard, is TextCommand.Keyboard, TextCommand.NewLine, TextCommand.ShowPassword -> emptyList()
        }

        // The phone commands act through Android's services; a call goes through Kestrel's own rule (CallCommands).
        private fun phoneReaches(command: PhoneCommand): List<Reach> = when (command) {
            is PhoneCommand.Global, is PhoneCommand.OpenApp, PhoneCommand.PreviousApp, PhoneCommand.CircleToSearch,
            is PhoneCommand.Radio, is PhoneCommand.Volume, is PhoneCommand.Mute, PhoneCommand.Silence, is PhoneCommand.Media,
            is PhoneCommand.Magnify, is PhoneCommand.Pan, is PhoneCommand.Call, PhoneCommand.CopyPhoneNumber -> emptyList()
        }

        private fun screenEdge(edge: Edge): ScreenEdge = when (edge) {
            Edge.TOP -> ScreenEdge.TOP
            Edge.BOTTOM -> ScreenEdge.BOTTOM
            Edge.LEFT -> ScreenEdge.LEFT
            Edge.RIGHT -> ScreenEdge.RIGHT
        }

        private fun scrollDirection(direction: Direction): ScrollDirection = when (direction) {
            Direction.UP -> ScrollDirection.UP
            Direction.DOWN -> ScrollDirection.DOWN
            Direction.LEFT -> ScrollDirection.LEFT
            Direction.RIGHT -> ScrollDirection.RIGHT
        }
    }
}

/**
 * "Copy this phone number" (design spec, Appendix A): the phone numbers in the texts of the app windows, never in the
 * status bar or the notifications. One number is copied at once; for several, Kestrel shows "Which one?" numbers on the
 * elements that hold them and asks. A background app cannot read the clipboard, so Kestrel only writes it.
 */
internal class NumberCopier(private val environment: CommandEnvironment) {
    private val screenCommands = ScreenCommands(environment)

    suspend fun copy(screen: ScreenSession, conversation: CommandConversation): CommandResult {
        val found = numbersOn(screen.snapshots.current().snapshot)
        val number = when (found.size) {
            0 -> return CommandResult(Outcome.FAILED, say = PhoneCommandTexts.NO_PHONE_NUMBER)
            1 -> found[0].second
            else -> when (val choice = screenCommands.choose(screen, found.map { ScreenTargets.chosen(it.first) }, conversation)) {
                is Choice.Chosen -> found.first { it.first.key == choice.element.key }.second
                is Choice.Ends -> return choice.result
            }
        }
        val clipboard = environment.context.getSystemService(ClipboardManager::class.java)
            ?: return CommandResult(Outcome.FAILED, show = PhoneCommandTexts.COULD_NOT_COPY)
        clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, number))
        return CommandResult(Outcome.DONE)
    }

    companion object {
        private const val CLIP_LABEL = "Phone number"

        /** Each element of an app window whose text or description shows a phone number, with its first one; each number once. */
        fun numbersOn(snapshot: ScreenSnapshot): List<Pair<ScreenElement, String>> {
            val apps = snapshot.windows.filter { it.kind == WindowKind.APPLICATION }.map { it.id }.toSet()
            return snapshot.elements.filter { it.windowId in apps && !it.password && !it.editable }.mapNotNull { element ->
                listOfNotNull(element.text, element.contentDescription).firstNotNullOfOrNull { text ->
                    PhoneNumbers.find(text).firstOrNull()?.let { text.substring(it) }
                }?.let { element to it }
            }.distinctBy { (_, number) -> number.filter(Char::isDigit) }
        }
    }
}
