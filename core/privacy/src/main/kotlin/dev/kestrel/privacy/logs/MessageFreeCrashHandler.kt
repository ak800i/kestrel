package dev.kestrel.privacy.logs

/**
 * Passes every uncaught error to Android's crash handler, [platform], as a copy without messages, because a message
 * can contain screen text (design spec, section 13.5). Android's crash report, and the crash log of failures that
 * reach this handler directly, such as coroutine failures, then contain only class names and stack frames.
 *
 * Android's own pre-handler still logs an error that is thrown directly on a thread, with its message, before any of
 * Kestrel's code runs. Guarding Kestrel's main-thread entry points closes that gap later (plan 1C).
 */
class MessageFreeCrashHandler(private val platform: Thread.UncaughtExceptionHandler) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        platform.uncaughtException(thread, error.withoutMessages())
    }
}
