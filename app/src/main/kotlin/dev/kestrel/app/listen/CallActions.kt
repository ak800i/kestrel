package dev.kestrel.app.listen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dev.kestrel.app.orchestrator.Request
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

/** Clicks a control on the screen through Kestrel's accessibility service. */
internal fun interface CallScreen {
    /**
     * Clicks the control whose content description or text is [label]. With [ifOff], a control that is on already, such
     * as a speaker control on a phone that puts the call on the speaker from the start, stays as it is. Returns whether a
     * control took the click, or was on already.
     */
    fun click(label: String, ifOff: Boolean): Boolean
}

/**
 * Carries out the call commands (design spec, section 8.7). With ANSWER_PHONE_CALLS, it answers, declines and hangs up
 * through Android's call API, which works on the lock screen and does not depend on the phone app's layout or
 * language. Without it, it clicks the incoming-call controls "Answer" and "Decline" through Kestrel's accessibility
 * service, which [screen] gives while the service runs: in the call's heads-up notification, or over the lock screen
 * (prototype P3). "Mute" and "Speaker" are only on the in-call screen, which it brings to the front first, and which
 * needs a moment to show them. The labels are the English ones of Google's phone app; the phone app's other languages
 * come with the matching of sub-project 3. It makes binder calls, so the caller runs it off the main thread.
 */
internal class CallActions(private val api: CallApi, private val screen: () -> CallScreen?) : CallControl {
    override suspend fun perform(command: Request.Call): String? = when (command) {
        is Request.Answer -> answer(command.speaker)
        Request.Decline -> decline()
        Request.HangUp -> hangUp()
        Request.Mute -> inCall(MUTE, ifOff = false, ListeningTexts.CANNOT_MUTE)
        // "Speaker" turns the speaker on, so a control that is on already stays on.
        Request.Speaker -> inCall(SPEAKER, ifOff = true, ListeningTexts.CANNOT_SPEAKER)
    }

    private suspend fun answer(speaker: Boolean): String? {
        if (api.allowed()) {
            api.answer()
        } else {
            val controls = screen() ?: return ListeningTexts.CALLS_NEED_ACCESS
            if (!waitFor(CONTROL_MILLIS) { controls.click(ANSWER, ifOff = false) }) return ListeningTexts.CANNOT_ANSWER
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
        return if (waitFor(CONTROL_MILLIS) { controls.click(DECLINE, ifOff = false) }) null else ListeningTexts.CANNOT_DECLINE
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
    // it there first.
    private suspend fun clickInCall(controls: CallScreen, label: String, ifOff: Boolean): Boolean {
        api.showInCallScreen()
        return waitFor(CONTROL_MILLIS) { controls.click(label, ifOff) }
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
