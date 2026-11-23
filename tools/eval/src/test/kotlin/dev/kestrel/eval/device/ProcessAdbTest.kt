package dev.kestrel.eval.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** adb's command lines, and a program that runs past its time (design D3 of sub-project 6). */
class ProcessAdbTest {
    @Test
    fun shellAndHostCommandsGoToAdbWithTheSerial() {
        val ran = mutableListOf<List<String>>()
        val adb = ProcessAdb("adb.exe", "emulator-5554") { command, _ -> ran.add(command); ShellResult(0, "", "") }

        adb.shell("dumpsys battery", 30.seconds)
        adb.host(listOf("emu", "avd", "name"), 30.seconds)

        assertEquals(listOf(listOf("adb.exe", "-s", "emulator-5554", "shell", "dumpsys battery"), listOf("adb.exe", "-s", "emulator-5554", "emu", "avd", "name")), ran)
        assertThrows(IllegalArgumentException::class.java) { adb.shell("echo \"x\"", 30.seconds) }
    }

    @Test
    fun aProgramRunsAndAnswers() {
        val result = LocalProcesses.run(listOf(java(), "-version"), 60.seconds)

        assertEquals(0, result.exitCode)
        assertTrue(result.err.contains("version"))
    }

    @Test
    fun aProgramThatRunsPastItsTimeIsEnded() {
        val command = listOf(java(), "-cp", System.getProperty("java.class.path"), SleepMain::class.java.name)

        val failure = assertThrows(AdbException::class.java) { LocalProcesses.run(command, 2.seconds) }

        assertTrue(failure.message!!.contains("did not end within 2s"))
    }

    private fun java(): String = ProcessHandle.current().info().command().get()
}

/** A program that only waits, for the test of the timeout. */
object SleepMain {
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(60_000)
    }
}
