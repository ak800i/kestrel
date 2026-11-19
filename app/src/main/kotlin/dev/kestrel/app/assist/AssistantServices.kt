package dev.kestrel.app.assist

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.MainActivity

/**
 * Kestrel's settings app with the launcher's own intent, so that a plain start brings Kestrel's task to the front instead
 * of a second MainActivity (design spec, sections 8.6 and 15). Started by the assistant's session, the settings open as
 * the assistant's activity, in a task of their own, beside a settings screen that the launcher opened, because Android
 * reuses only a task of the same activity type.
 */
internal fun settingsIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
    .setAction(Intent.ACTION_MAIN)
    .addCategory(Intent.CATEGORY_LAUNCHER)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * Makes Kestrel eligible for Android's assistant role (design spec, section 8.6; research r6, prototype P3). Android
 * binds it while Kestrel holds the role, also after a restart, so it runs the restore of the listening mode (section
 * 8.5). The assistant gesture opens [KestrelSessionService], and so does [openSettings].
 */
class KestrelAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        ready = this
        // An owner who uses the role without the accessibility service also gets listening back after a restart.
        (applicationContext as KestrelApplication).graph.listening.onAssistantServiceReady()
        // Kestrel reads the screen through its accessibility service, so Android need not collect the app's text or a
        // screenshot for the session at every gesture (section 13).
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }

    override fun onShutdown() {
        if (ready === this) ready = null
        super.onShutdown()
    }

    companion object {
        /** The argument of a session that opens Kestrel's settings instead of starting to listen. */
        internal const val EXTRA_OPEN_SETTINGS = "dev.kestrel.app.extra.OPEN_SETTINGS"

        // The service that Android has bound for the role, from onReady until onShutdown, which clears it, so it cannot
        // keep a service that Android has let go.
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var ready: KestrelAssistantService? = null

        /**
         * "Kestrel, open settings" with only the assistant role (design spec, section 8.6): asks Android for a session of
         * Kestrel's assistant, which starts the settings, and returns whether a service was there to ask. Android lets
         * the assistant's shown session start an activity also over another app, while Android 13 most likely blocks a
         * plain start from the background then (A71 session of sub-project 2, item 5). Any thread may call it; it
         * throws when Android refuses the session.
         */
        internal fun openSettings(): Boolean {
            val service = ready ?: return false
            service.showSession(Bundle().apply { putBoolean(EXTRA_OPEN_SETTINGS, true) }, 0)
            return true
        }
    }
}

/** Opens an [AssistSession] each time the owner calls the assistant (design spec, section 8.6). */
class KestrelSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * The assistant gesture, such as a long press of Home or the side key (prototype P3): Kestrel listens and waits for a
 * task. A session that [KestrelAssistantService.openSettings] asked for starts Kestrel's settings instead. The session's
 * window would cover the screen and take every touch and key, so the session hides it at once.
 */
private class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val graph = (context.applicationContext as KestrelApplication).graph
        if (args?.getBoolean(KestrelAssistantService.EXTRA_OPEN_SETTINGS) == true) {
            // A shown session may start the assistant's activity, also over another app. AppGraph waits for the settings
            // and says so when they do not come.
            try {
                startAssistantActivity(settingsIntent(context))
            } catch (e: RuntimeException) {
                graph.report("Opening the settings from the assistant's session", e)
            }
            hide()
            return
        }
        graph.timeline.start("assistant")
        graph.listening.assist()
        hide()
    }
}

/**
 * A speech recognition service, which Android's assistant role requires of a VoiceInteractionService (research r6).
 * Kestrel offers no speech recognition to other apps, so it refuses every request. Only Android can bind it.
 */
class KestrelRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: RecognitionService.Callback?) {
        try {
            listener?.error(SpeechRecognizer.ERROR_CLIENT)
        } catch (e: RemoteException) {
            // The caller has gone, so nobody is left to tell.
        }
    }

    override fun onCancel(listener: RecognitionService.Callback?) = Unit

    override fun onStopListening(listener: RecognitionService.Callback?) = Unit
}
