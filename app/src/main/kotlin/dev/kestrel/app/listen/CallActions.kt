package dev.kestrel.app.listen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.kestrel.app.orchestrator.Request
import dev.kestrel.screen.act.ScreenActions
import kotlinx.coroutines.delay

/** What Kestrel does with a call command (design spec, section 8.7). */
internal fun interface CallControl {
    /** Carries out [command]. Returns null when it worked, or why it did not, as a text for the feedback bar. */
    suspend fun perform(command: Request.Call): String?
}

/** Android's call API, which needs ANSWER_PHONE_CALLS, and the phone's call state, which needs READ_PHONE_STATE. */
internal interface CallApi {
    /** Whether Kestrel holds ANSWER_PHONE_CALLS. */
    fun allowed(): Boolean

    /** Answers the ringing call. */
    fun answer()

    /** Declines the ringing call, or ends the active one. Returns false when there was none. */
    fun end(): Boolean

    /** Whether a call is active. */
    fun active(): Boolean

    /** Brings the phone app's in-call screen to the front, where its speaker and mute controls are. */
    fun showInCallScreen()
}

/**
 * The call's controls on the screen, through Kestrel's accessibility service: only in the windows of the phone app,
 * which shows the incoming-call and in-call screens, and, for the incoming call, of SystemUI, which shows the call's
 * heads-up notification, so that a control with the same label in another app, such as a video player's "Mute", is
 * never clicked.
 */
internal interface CallScreen {
    /** Whether the phone app's in-call screen is the app window in front. */
    fun inCallShown(): Boolean

    /**
     * Clicks the incoming call's control whose content description or text is [label], in the windows of the phone app
     * and of SystemUI only. Returns whether a control took the click.
     */
    fun clickIncoming(label: String): Boolean

    /**
     * Clicks the in-call screen's control whose content description or text is [label], in the phone app's windows
     * only: SystemUI's windows lie above the in-call screen, and another app's heads-up notification there can have an
     * action with the same label. With [ifOff], a control that is on already, such as a speaker control on a phone that
     * puts the call on the speaker from the start, stays as it is. Returns whether a control took the click, or was on
     * already.
     */
    fun clickInCall(label: String, ifOff: Boolean): Boolean
}

/**
 * [CallScreen] through Kestrel's accessibility service [actions]. The phone app is Android's default phone app or a
 * known in-call screen, because an in-call screen, such as One UI's, may be in a package of its own.
 */
internal class AndroidCallScreen(private val context: Context, private val actions: ScreenActions) : CallScreen {
    override fun inCallShown(): Boolean = actions.frontApp() in phoneApps()

    override fun clickIncoming(label: String): Boolean = actions.clickLabelled(label, phoneApps() + SYSTEM_UI)

    override fun clickInCall(label: String, ifOff: Boolean): Boolean = actions.clickLabelled(label, phoneApps(), ifOff)

    // Read at each use, because the owner can choose another phone app at any time.
    private fun phoneApps(): Set<String> =
        IN_CALL_APPS + listOfNotNull(context.getSystemService(TelecomManager::class.java).defaultDialerPackage)

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        val IN_CALL_APPS = setOf("com.google.android.dialer", "com.android.dialer", "com.samsung.android.incallui")
    }
}

/**
 * Carries out the call commands (design spec, section 8.7). With ANSWER_PHONE_CALLS, it answers, declines and hangs up
 * through Android's call API, which works on the lock screen and does not depend on the phone app's layout or
 * language. Without it, it clicks the incoming-call controls "Answer" and "Decline" through Kestrel's accessibility
 * service, which [screen] gives while the service runs: in the call's heads-up notification, or over the lock screen
 * (prototype P3). "Mute" and "Speaker" are only on the in-call screen, which it brings to the front first, and which
 * needs a moment to come there and to show them; it clicks only once that screen is in front, and only in the phone
 * app's windows. "Answer" and "Decline" are searched in the phone app's and SystemUI's windows. The labels are the
 * English ones of Google's phone app; the phone app's other languages come with the matching of sub-project 3. It makes
 * binder calls, so the caller runs it off the main thread.
 */
internal class CallActions(private val api: CallApi, private val screen: () -> CallScreen?) : CallControl {
    override suspend fun perform(command: Request.Call): String? = when (command) {
        is Request.Answer -> answer(command.speaker)
        Request.Decline -> decline()
        Request.HangUp -> hangUp()
        Request.Mute -> inCall(MUTE, ifOff = true, ListeningTexts.CANNOT_MUTE)
        // "Mute" and "Speaker" turn their control on, so a control that is on already stays on, and a second "mute"
        // does not unmute the call.
        Request.Speaker -> inCall(SPEAKER, ifOff = true, ListeningTexts.CANNOT_SPEAKER)
    }

    private suspend fun answer(speaker: Boolean): String? {
        if (api.allowed()) {
            api.answer()
        } else {
            val controls = screen() ?: return ListeningTexts.CALLS_NEED_ACCESS
            if (!waitFor(CONTROL_MILLIS) { controls.clickIncoming(ANSWER) }) return ListeningTexts.CANNOT_ANSWER
        }
        if (!speaker) return null
        // The in-call screen shows its speaker control once the call is active.
        val controls = screen()
        if (controls == null || !waitFor(ACTIVE_MILLIS) { api.active() } || !clickInCall(controls, SPEAKER, ifOff = true)) {
            return ListeningTexts.ANSWERED_WITHOUT_SPEAKER
        }
        return null
    }

    private suspend fun decline(): String? {
        if (api.allowed()) return if (api.end()) null else ListeningTexts.CANNOT_DECLINE
        val controls = screen() ?: return ListeningTexts.CALLS_NEED_ACCESS
        return if (waitFor(CONTROL_MILLIS) { controls.clickIncoming(DECLINE) }) null else ListeningTexts.CANNOT_DECLINE
    }

    private suspend fun hangUp(): String? {
        if (api.allowed()) return if (api.end()) null else ListeningTexts.CANNOT_HANG_UP
        // Without the permission, it clicks the in-call screen's "End call" control, which Google's phone app has on
        // kestrel-api37 (plan 2D's Task 3), and says that it needs the permission only when it finds no such control.
        val controls = screen() ?: return ListeningTexts.HANG_UP_NEEDS_PERMISSION
        return if (clickInCall(controls, END_CALL, ifOff = false)) null else ListeningTexts.HANG_UP_NEEDS_PERMISSION
    }

    private suspend fun inCall(label: String, ifOff: Boolean, failed: String): String? {
        val controls = screen() ?: return ListeningTexts.CALLS_NEED_SERVICE
        return if (clickInCall(controls, label, ifOff)) null else failed
    }

    // The in-call screen does not come to the front after an answer through the API (prototype P3), so Kestrel brings
    // it there first, and clicks only once it is in front. Until then the app that the owner uses is, and its control
    // with the same label must never count.
    private suspend fun clickInCall(controls: CallScreen, label: String, ifOff: Boolean): Boolean {
        api.showInCallScreen()
        // The phone app sometimes leaves a request unanswered, as on kestrel-api37 a few seconds after the answer of a
        // call, so Kestrel asks again every second while it waits.
        var polls = 0
        val inFront = waitFor(CONTROL_MILLIS) {
            val shown = controls.inCallShown()
            if (!shown && ++polls % ASK_AGAIN_POLLS == 0) api.showInCallScreen()
            shown
        }
        if (!inFront) return false
        return waitFor(CONTROL_MILLIS) { controls.clickInCall(label, ifOff) }
    }

    // Tries [condition] every POLL_MILLIS until it is true or [millis] have passed.
    private suspend fun waitFor(millis: Long, condition: () -> Boolean): Boolean {
        var waited = 0L
        while (!condition()) {
            if (waited >= millis) return false
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        return true
    }

    private companion object {
        // The labels of Google's phone app on the emulator: the incoming-call controls from prototype P3, the in-call
        // controls from Step 1 of plan 2D's Task 3.
        const val ANSWER = "Answer"
        const val DECLINE = "Decline"
        const val END_CALL = "End call"
        const val MUTE = "Mute"
        const val SPEAKER = "Speaker"

        // How long a call may take to become active after the answer, and a control to show.
        const val ACTIVE_MILLIS = 5_000L
        const val CONTROL_MILLIS = 3_000L
        const val POLL_MILLIS = 200L

        // Every second.
        const val ASK_AGAIN_POLLS = 5
    }
}

/**
 * Android's call API (research r8). acceptRingingCall() and endCall() are deprecated, but Android 17 still has them, and
 * they are the only way for an app that is not the phone app. Each call checks its permission first, because the owner
 * can take it back at any time.
 */
internal class AndroidCallApi(private val context: Context) : CallApi {
    private val telecom = context.getSystemService(TelecomManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    override fun allowed(): Boolean =
        context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    override fun answer() {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Kestrel may not answer calls")
        }
        telecom.acceptRingingCall()
    }

    @Suppress("DEPRECATION")
    override fun end(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) return false
        return telecom.endCall()
    }

    // Telecom's view covers the calls of every SIM, and isInCall() follows the call (prototype P3). TelecomManager's
    // isRinging() is a system API, so the device-wide call state tells a ringing call apart: OFFHOOK means that a call
    // is active, dialing or on hold, and that none rings.
    @Suppress("DEPRECATION")
    override fun active(): Boolean {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return false
        return telecom.isInCall && telephony.callState == TelephonyManager.CALL_STATE_OFFHOOK
    }

    override fun showInCallScreen() {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        telecom.showInCallScreen(false)
    }
}
