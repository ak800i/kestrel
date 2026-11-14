package dev.kestrel.privacy.logs

import java.util.IdentityHashMap

private const val MAX_CAUSES = 8
private const val MAX_FRAMES = 30

/**
 * Describes this error by its class names and stack frames only, for logcat. Messages are left out, because
 * they can contain screen text or other personal data (design spec, section 13.5).
 */
fun Throwable.describeWithoutMessages(): String = buildString {
    var error: Throwable? = this@describeWithoutMessages
    var depth = 0
    while (error != null && depth < MAX_CAUSES) {
        if (depth > 0) append("Caused by: ")
        append(error.javaClass.name)
        for (frame in error.stackTrace.take(MAX_FRAMES)) append("\n    at ").append(frame)
        append('\n')
        error = error.cause?.takeIf { it !== error }
        depth++
    }
}.trimEnd()

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
        val copy = MessageFreeCopy(error.javaClass.name, error.stackTrace)
        if (depth + 1 < MAX_CAUSES) {
            error.cause?.let { copyOf(it, depth + 1) }?.let(copy::initCause)
            for (suppressed in error.suppressed) copyOf(suppressed, depth + 1)?.let(copy::addSuppressed)
        }
        copies[error] = copy
        return copy
    }
    return checkNotNull(copyOf(this, depth = 0))
}

/** A copy of an error that shows only the original class name and stack frames. */
private class MessageFreeCopy(private val className: String, stackTrace: Array<StackTraceElement>) : Throwable() {
    init {
        this.stackTrace = stackTrace
    }

    override fun toString(): String = className
}
