package dev.kestrel.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** The first form of the command line: the usage, and exit code 2 for anything it does not know (design D2 of sub-project 6). */
class CliTest {
    @Test
    fun withoutACommandItPrintsTheUsageAndRefuses() {
        val bytes = ByteArrayOutputStream()

        val code = Cli.run(emptyList(), PrintStream(bytes, true, Charsets.UTF_8))

        assertEquals(Cli.EXIT_REFUSED, code)
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("Usage: scripts\\eval.ps1 <command> [options]"))
    }

    @Test
    fun anUnknownCommandIsNamedAndRefused() {
        val bytes = ByteArrayOutputStream()

        val code = Cli.run(listOf("fly"), PrintStream(bytes, true, Charsets.UTF_8))

        assertEquals(Cli.EXIT_REFUSED, code)
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("Unknown command fly."))
    }
}
