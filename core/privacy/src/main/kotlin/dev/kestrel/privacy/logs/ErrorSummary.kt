package dev.kestrel.privacy.logs

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
