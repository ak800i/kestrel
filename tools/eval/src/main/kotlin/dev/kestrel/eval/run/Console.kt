package dev.kestrel.eval.run

import dev.kestrel.eval.config.Redactions
import java.io.PrintStream

/** Where :tools:eval reports: the run lines and its own sentences, and warnings, which never hold screen text. */
interface Console {
    fun line(text: String)

    /** A warning, whose text [Redactions.forConsole] has cleaned. */
    fun warn(text: String)
}

/** [Console] on a stream; a warning keeps only what [Redactions] lets through. Both threads of a stop may print. */
class PrintConsole(private val out: PrintStream, private val redactions: Redactions) : Console {
    override fun line(text: String) = synchronized(this) { out.println(text) }

    override fun warn(text: String) = synchronized(this) { out.println("WARNING: ${redactions.forConsole(text)}") }
}
