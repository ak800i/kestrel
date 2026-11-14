package dev.kestrel.privacy.logs

import android.os.Handler
import android.os.Looper

/**
 * Makes an error that is thrown on the main thread reach Android without its message (design spec, section 13.5).
 * Android's own pre-handler logs an uncaught error, with its message, before any handler runs. So the main thread's
 * message loop runs inside a loop of Kestrel's, which catches the error and throws a copy without messages instead,
 * and Android then logs and reports only the copy. The inner loop makes Android log one warning, "Loop again would
 * have the queued messages be executed before this one completed", when it starts. Errors that happen before it
 * starts, while Android creates the app, are not covered.
 */
object MainThreadErrors {
    /** Call it once, when the app is created; the inner loop starts with the main thread's next message. */
    fun install() {
        Handler(Looper.getMainLooper()).postAtFrontOfQueue {
            try {
                Looper.loop()
            } catch (error: Throwable) {
                throw error.withoutMessages()
            }
        }
    }
}
