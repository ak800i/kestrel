package dev.kestrel.privacy.logs

/**
 * Passes every uncaught error to Android's crash handler, [platform], as a copy without messages, because a message
 * can contain screen text (design spec, section 13.5). Android's crash report, and the crash log of failures that
 * reach this handler directly, such as coroutine failures, then contain only class names and stack frames. Android's
 * crash record names the copy's own class, `MessageFreeCopy`, as the exception class, while the stack text keeps the
 * real class names.
 *
 * When the copy cannot be made, for example because the heap is full, the handler passes on a placeholder,
 * `CrashCopyFailed`, which has no message and no stack frames. It never passes on the original error, and it always
 * calls [platform], which reports the crash and ends the process.
 *
 * Android's own pre-handler still logs an error that is thrown directly on a thread, with its message, before any of
 * Kestrel's code runs. Guarding Kestrel's main-thread entry points closes that gap later (plan 1C).
 */
class MessageFreeCrashHandler(private val platform: Thread.UncaughtExceptionHandler) : Thread.UncaughtExceptionHandler {
    // Made up front, because a copy can fail when the heap is full, and then there may be no memory left to make it.
    private val copyFailed: Throwable = CrashCopyFailed()

    override fun uncaughtException(thread: Thread, error: Throwable) {
        // Android's handler must run whatever happens: it reports the crash and ends the process.
        val copy = try {
            error.withoutMessages()
        } catch (e: Throwable) {
            copyFailed
        }
        platform.uncaughtException(thread, copy)
    }
}

/**
 * Stands in for an error whose copy could not be made. It has no message and no stack frames, and nothing can add a
 * cause or suppressed errors to it, so one instance serves every crash.
 */
private class CrashCopyFailed : Throwable(null, null, false, false) {
    // The same text as Throwable's own toString() gives without a message, but read up front, like the placeholder.
    private val name: String = javaClass.name

    override fun toString(): String = name
}
