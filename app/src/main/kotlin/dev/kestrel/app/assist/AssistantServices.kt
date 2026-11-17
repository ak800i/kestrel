package dev.kestrel.app.assist

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

/**
 * Makes Kestrel eligible for Android's assistant role (design spec, section 8.6; research r6, prototype P3). Android
 * binds it while Kestrel holds the role, also after a restart, so it runs the restore of the listening mode (section
 * 8.5). The assistant gesture opens [KestrelSessionService].
 */
class KestrelAssistantService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        // An owner who uses the role without the accessibility service also gets listening back after a restart.
        (applicationContext as KestrelApplication).graph.listening.onAssistantServiceReady()
        // Kestrel reads the screen through its accessibility service, so Android need not collect the app's text or a
        // screenshot for the session at every gesture (section 13).
        setDisabledShowContext(VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
    }
}

/** Opens an [AssistSession] each time the owner calls the assistant (design spec, section 8.6). */
class KestrelSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistSession(this)
}

/**
 * The assistant gesture, such as a long press of Home or the side key (prototype P3): Kestrel listens and waits for a
 * task. The session's window would cover the screen and take every touch and key, so the session hides it at once.
 */
private class AssistSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        (context.applicationContext as KestrelApplication).graph.listening.assist()
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
