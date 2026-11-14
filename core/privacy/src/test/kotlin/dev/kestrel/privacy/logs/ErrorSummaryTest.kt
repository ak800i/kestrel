package dev.kestrel.privacy.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorSummaryTest {
    @Test
    fun leavesOutMessagesButKeepsClassesFramesAndCauses() {
        val error = IllegalStateException("Message from Ana: see you at 5", IOException("token=secret"))

        val text = error.describeWithoutMessages()

        assertFalse(text, text.contains("Ana"))
        assertFalse(text, text.contains("secret"))
        assertTrue(text, text.startsWith("java.lang.IllegalStateException\n    at dev.kestrel.privacy.logs.ErrorSummaryTest"))
        assertTrue(text, text.contains("Caused by: java.io.IOException"))
    }

    @Test
    fun theCopyHasNoMessagesButKeepsClassesFramesCausesAndSuppressedErrors() {
        val error = IllegalStateException("secret A", IOException("secret B"))
        error.addSuppressed(RuntimeException("secret C"))

        val copy = error.withoutMessages()

        val text = copy.stackTraceToString()
        // Android's crash report reads the message of the error and of every cause.
        for (part in listOf(copy, copy.cause!!, copy.suppressed.single())) {
            assertNull(part.toString(), part.message)
            assertNull(part.toString(), part.localizedMessage)
        }
        assertEquals(error.stackTrace.toList(), copy.stackTrace.toList())
        assertEquals("java.lang.IllegalStateException", text.lines().first())
        assertTrue(text, text.contains(error.stackTrace.first().methodName))
        assertTrue(text, text.contains("Caused by: java.io.IOException"))
        assertTrue(text, text.contains("Suppressed: java.lang.RuntimeException"))
        assertFalse(text, text.contains("secret"))
    }

    @Test(timeout = 5_000)
    fun theCopyEndsACycleOfCauses() {
        val error = IllegalStateException("secret A")
        error.initCause(IOException("secret B", error))

        val copy = error.withoutMessages()

        val chain = generateSequence(copy) { it.cause }.take(10).map { it.toString() }.toList()
        assertEquals(listOf("java.lang.IllegalStateException", "java.io.IOException"), chain)
    }

    @Test
    fun theCrashHandlerPassesOnACopyWithoutMessages() {
        var passedOn: Throwable? = null
        val handler = MessageFreeCrashHandler { _, error -> passedOn = error }

        handler.uncaughtException(Thread.currentThread(), IllegalStateException("secret A", IOException("secret B")))

        val text = passedOn!!.stackTraceToString()
        assertEquals("java.lang.IllegalStateException", text.lines().first())
        assertTrue(text, text.contains("Caused by: java.io.IOException"))
        assertFalse(text, text.contains("secret"))
    }

    @Test
    fun describesEachErrorOfTheChainSeparately() {
        val error = IllegalStateException("secret A", IOException("secret B", RuntimeException("secret C")))

        val parts = error.describeWithoutMessagesByCause()

        assertEquals(listOf("java.lang.IllegalStateException", "Caused by: java.io.IOException", "Caused by: java.lang.RuntimeException"), parts.map { it.lines().first() })
        assertFalse(parts.joinToString(), parts.any { it.contains("secret") })
        assertEquals(error.describeWithoutMessages(), parts.joinToString("\n"))
    }

    @Test
    fun aCopyOfACopyKeepsTheOriginalClassName() {
        val copy = IllegalStateException("secret").withoutMessages().withoutMessages()

        assertEquals("java.lang.IllegalStateException", copy.toString())
        assertEquals("java.lang.IllegalStateException", copy.describeWithoutMessages().lines().first())
    }

    @Test
    fun theCrashHandlerPassesOnAPlaceholderWhenTheCopyFails() {
        val passedOn = mutableListOf<Throwable>()
        val handler = MessageFreeCrashHandler { _, error -> passedOn += error }
        // The copy needs the error's stack frames, so it cannot be made. The failure is an Error, as when memory has run out.
        val error = object : IllegalStateException("secret A") {
            override fun getStackTrace(): Array<StackTraceElement> = throw StackOverflowError("secret B")
        }

        handler.uncaughtException(Thread.currentThread(), error)

        assertEquals(1, passedOn.size)
        val text = passedOn[0].stackTraceToString()
        assertNull(passedOn[0].message)
        assertFalse(text, text.contains("secret"))
        assertFalse(text, text.contains("StackOverflowError"))
    }
}
