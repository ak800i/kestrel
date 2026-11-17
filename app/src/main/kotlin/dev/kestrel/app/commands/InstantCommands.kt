package dev.kestrel.app.commands

import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.commands.PhoneCommand
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * The executor of the instant commands (design spec, section 9.5). It asks the text part, then the phone part, then the
 * screen part; when none takes a command, it runs the transcript once more as a bare label through the screen part, and
 * a result that acted then names that bare label as the command that acted, for "again". Without Kestrel's
 * accessibility service, only the volume, mute, silence and media commands run.
 */
internal class InstantCommands(private val environment: CommandEnvironment) : CommandRunner {
    private val text = textCommands(environment)
    private val phone = phoneCommands(environment)
    private val screen = screenCommands(environment)

    override suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult {
        val session = environment.screen()
        if (session == null && !withoutScreen(command)) {
            return if (command is Command.Bare) CommandResult(Outcome.IGNORED) else CommandResult(Outcome.FAILED, say = CommandTexts.SERVICE_OFF)
        }
        val talk = if (session == null) conversation else TouchCancels(conversation, session)
        for (part in listOf(text, phone, screen)) {
            part.run(command, heard, talk)?.let { return it }
        }
        val bare = Command.Bare.of(heard)
        val result = screen.run(bare, heard, talk) ?: CommandResult(Outcome.IGNORED)
        return if (result.outcome == Outcome.DONE) result.copy(acted = bare) else result
    }

    override fun privateInput(): Boolean = text.privateInput() || phone.privateInput() || screen.privateInput()

    private fun withoutScreen(command: Command): Boolean =
        command is PhoneCommand.Volume || command is PhoneCommand.Mute || command is PhoneCommand.Silence ||
            command is PhoneCommand.Media
}

/**
 * Cancels a command's question or countdown when the owner touches the screen (design spec, section 14.1): while [ask] or
 * [countdown] waits, the touch watcher runs, and a touch makes [ask] return null and [countdown] return false, so the
 * command ends as cancelled.
 */
private class TouchCancels(private val inner: CommandConversation, private val screen: ScreenSession) : CommandConversation {
    override suspend fun say(text: String) = inner.say(text)

    override fun keep(text: String) = inner.keep(text)

    override suspend fun ask(question: String): String? = untilTouched(null) { inner.ask(question) }

    override suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean =
        untilTouched(false) { inner.countdown(millis, text) }

    private suspend fun <T> untilTouched(touched: T, block: suspend () -> T): T = coroutineScope {
        // Subscribed before the watcher starts, so that no touch is lost in between.
        val touch = async(start = CoroutineStart.UNDISPATCHED) { screen.ownerTouches.first() }
        try {
            screen.startTouchWatcher()
            val work = async { block() }
            select<T> {
                work.onAwait { it }
                touch.onAwait { touched }
            }.also { work.cancel() }
        } finally {
            touch.cancel()
            withContext(NonCancellable) { screen.stopTouchWatcher() }
        }
    }
}
