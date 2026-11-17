package dev.kestrel.app.commands

import androidx.test.platform.app.InstrumentationRegistry
import dev.kestrel.app.AppGraph
import dev.kestrel.app.KestrelApplication
import dev.kestrel.app.SettingKeys
import dev.kestrel.app.listen.VoiceSessions
import dev.kestrel.app.orchestrator.Event
import dev.kestrel.app.orchestrator.Mode
import dev.kestrel.app.voice.VoiceTests
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Drives instant commands on the device through the same path as speech (design D15 of sub-project 3): a text goes to
 * Kestrel's Orchestrator as a transcript without the name, and the test waits for the command's report. The voice session
 * is [TextSessions], so the tests need no speech models. Every command test skips itself unless the instrumentation
 * argument [ARGUMENT] is true, which only scripts\command-test.ps1 passes, because only that script puts back what the
 * tests change on the device.
 */
internal object CommandTests {
    const val ARGUMENT = "kestrelCommands"
    private const val REPORT_MILLIS = 15_000L

    val graph: AppGraph
        get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KestrelApplication).graph

    /**
     * Skips each test unless [ARGUMENT] is true, before any other rule runs: every command test class applies it first,
     * as its rule of order 0, so that a skipped test never reaches ScreenServiceRule, which changes the device.
     */
    val only: TestRule
        get() = TestRule { base: Statement, _: Description ->
            object : Statement() {
                override fun evaluate() {
                    assumeCommands()
                    base.evaluate()
                }
            }
        }

    private var sessions: TextSessions? = null
    private var previousSessions: VoiceSessions? = null
    private var collector: Job? = null
    private val reports = Channel<CommandReport>(Channel.UNLIMITED)

    /** What Kestrel said since [start]. */
    val spoken: List<String> get() = sessions?.spoken.orEmpty()

    /** Whether the running command waits for the answer to its question. */
    val asked: Boolean get() = graph.listening.state.value.asking

    fun assumeCommands() =
        assumeTrue("Runs only through scripts\\command-test.ps1", InstrumentationRegistry.getArguments().getString(ARGUMENT) == "true")

    /**
     * Turns listening on with [TextSessions], and waits until the session is open and Kestrel listens. Kestrel's
     * accessibility service must be on (ScreenServiceRule), so that Android lets it start its listening service.
     */
    fun start() {
        assumeCommands()
        VoiceTests.grantListeningPermissions()
        // A test process that died may have left other options stored, and command-test.ps1 does not reset Kestrel's
        // settings.
        runBlocking { graph.settings.set(SettingKeys.COMMANDS, CommandOptions()) }
        check(VoiceTests.waitUntil(5_000) { graph.commandOptions.value == CommandOptions() }) { "The options did not reset" }
        val listening = graph.listening
        // An earlier test that failed may have left Kestrel on.
        if (listening.state.value.mode != Mode.OFF) turnOff()
        val text = TextSessions()
        if (previousSessions == null) previousSessions = listening.sessions
        listening.sessions = text
        sessions = text
        // Drops the reports of an earlier test.
        while (reports.tryReceive().isSuccess) continue
        collector = graph.scope.launch(start = CoroutineStart.UNDISPATCHED) { listening.commandReports.collect { reports.send(it) } }
        listening.send(Event.Start())
        check(VoiceTests.waitUntil(15_000) { listening.sessionOpen.value && listening.state.value.mode == Mode.LISTENING }) {
            "Listening did not start; the mode is ${listening.state.value.mode}"
        }
    }

    /** Turns Kestrel off, waits until the session has closed and the service has stopped, and puts the sessions back. */
    fun stop() {
        if (sessions == null) return
        try {
            turnOff()
        } finally {
            collector?.cancel()
            collector = null
            previousSessions?.let { graph.listening.sessions = it }
            previousSessions = null
            sessions = null
        }
    }

    private fun turnOff() {
        val listening = graph.listening
        listening.send(Event.TurnOff)
        check(VoiceTests.waitUntil(30_000) { listening.state.value.mode == Mode.OFF && !listening.sessionOpen.value }) {
            "Listening did not stop"
        }
        check(VoiceTests.waitUntil(10_000) { !VoiceTests.listeningServiceRuns() }) { "ListeningService still runs" }
    }

    /** Sends [text] as a transcript without the name, as speech does. */
    fun send(text: String) = graph.listening.send(Event.Heard(text, named = false))

    /** Sends [text] and waits for the report of the command that it started. */
    fun say(text: String, timeoutMillis: Long = REPORT_MILLIS): CommandReport {
        send(text)
        return next(timeoutMillis)
    }

    /** Sends [text], waits until its command asks, and returns what Kestrel asked. */
    fun ask(text: String, timeoutMillis: Long = REPORT_MILLIS): String {
        val before = spoken.size
        send(text)
        check(VoiceTests.waitUntil(timeoutMillis) { asked && spoken.size > before }) { "The command asked nothing" }
        return spoken.last()
    }

    /** Answers the running command's question with [text], and waits for the command's report. */
    fun answer(text: String, timeoutMillis: Long = REPORT_MILLIS): CommandReport = say(text, timeoutMillis)

    /** Waits for the report of the next command that ends. */
    fun next(timeoutMillis: Long = REPORT_MILLIS): CommandReport =
        runBlocking { withTimeoutOrNull(timeoutMillis) { reports.receive() } }
            ?: throw AssertionError("No command ended within $timeoutMillis ms")

    /** Waits until Kestrel has said [text]; the spoken result of a command comes a moment after its report. */
    fun awaitSaid(text: String, timeoutMillis: Long = 5_000) {
        check(VoiceTests.waitUntil(timeoutMillis) { spoken.lastOrNull() == text }) { "Kestrel did not say the expected text" }
    }
}
