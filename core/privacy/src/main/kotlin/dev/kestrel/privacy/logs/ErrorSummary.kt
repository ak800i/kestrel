package dev.kestrel.privacy.logs

import java.util.IdentityHashMap

private const val MAX_CAUSES = 8
private const val MAX_FRAMES = 30

/**
 * Describes this error by its class names and stack frames only, for logcat. Messages are left out, because
 * they can contain screen text or other personal data (design spec, section 13.5).
 */
fun Throwable.describeWithoutMessages(): String = describeWithoutMessagesByCause().joinToString("\n")

/**
 * The same description as [describeWithoutMessages], as one text for each error in the chain of causes, because
 * Android's log cuts an entry after about 4 KB.
 */
fun Throwable.describeWithoutMessagesByCause(): List<String> {
    val parts = mutableListOf<String>()
    var error: Throwable? = this
    while (error != null && parts.size < MAX_CAUSES) {
        val current: Throwable = error
        parts += buildString {
            if (parts.isNotEmpty()) append("Caused by: ")
            append(current.originalClassName())
            for (frame in current.stackTrace.take(MAX_FRAMES)) append("\n    at ").append(frame)
        }
        error = current.cause?.takeIf { it !== current }
    }
    return parts
}

/**
 * Returns a copy of this error without messages, for Android's crash handler (design spec, section 13.5). The copy,
 * its causes and its suppressed errors keep their stack frames, and their [toString] gives only the original class
 * name, such as `java.lang.IllegalStateException`, while their messages are null. Like [describeWithoutMessages],
 * the copy keeps at most [MAX_CAUSES] errors of a chain of causes. A cause that leads back to an earlier error is
 * left out, so that the copy's chain of causes always ends.
 */
fun Throwable.withoutMessages(): Throwable {
    // An error maps to null while its copy is being made, so that a link back to it is left out instead of closing a cycle.
    val copies = IdentityHashMap<Throwable, Throwable?>()
    fun copyOf(error: Throwable, depth: Int): Throwable? {
        if (error in copies) return copies[error]
        copies[error] = null
        val copy = MessageFreeCopy(error.originalClassName(), error.stackTrace)
        if (depth + 1 < MAX_CAUSES) {
            error.cause?.let { copyOf(it, depth + 1) }?.let(copy::initCause)
            for (suppressed in error.suppressed) copyOf(suppressed, depth + 1)?.let(copy::addSuppressed)
        }
        copies[error] = copy
        return copy
    }
    return checkNotNull(copyOf(this, depth = 0))
}

/** The class name of the error that this error copies, if it is a copy, so that a copy of a copy keeps the original name. */
private fun Throwable.originalClassName(): String = (this as? MessageFreeCopy)?.className ?: javaClass.name

/**
 * A copy of an error that shows only the original class name and stack frames. It is a RuntimeException, because
 * Android rethrows those from the main thread as they are, while it wraps other kinds of errors in a RuntimeException.
 */
private class MessageFreeCopy(val className: String, stackTrace: Array<StackTraceElement>) : RuntimeException() {
    init {
        this.stackTrace = stackTrace
    }

    override fun toString(): String = className
}
