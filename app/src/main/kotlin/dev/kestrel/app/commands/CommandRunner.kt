package dev.kestrel.app.commands

import android.content.Context
import dev.kestrel.app.settings.SettingsPage
import dev.kestrel.commands.Command
import dev.kestrel.commands.CommandResult
import dev.kestrel.commands.Outcome
import dev.kestrel.screen.ScreenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Runs the instant commands (design spec, sections 7.3 and 9.5). `Listening` calls [run] in a job of its own, one
 * command at a time, and cancels the job when the owner says "stop" or "cancel" or the mode changes.
 */
internal interface CommandRunner {
    /** Runs [command], which was parsed from the transcript [heard], and returns how it ended. */
    suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult

    /**
     * Whether a password field has the focus, so that the feedback bar must not show what Kestrel heard (section 13.5).
     * The controller's thread calls it before each transcript is shown, so it makes no binder call.
     */
    fun privateInput(): Boolean
}

/** How a running command talks to the owner (design spec, section 7.3). */
internal interface CommandConversation {
    /** Says [text] and shows it on the bar; returns when the reply has ended in any way, or at once without a voice session. */
    suspend fun say(text: String)

    /**
     * Asks [question], keeps it on the bar, and returns the next transcript that is not Kestrel's own request nor a stop,
     * or null when the owner did not answer in time.
     */
    suspend fun ask(question: String): String?

    /** Shows [text] on the bar until the command ends or the next text, as a numbered list. */
    fun keep(text: String)

    /**
     * Counts down [millis], keeping `text(secondsLeft)` on the bar, and returns true when the time has run out. The
     * owner's speech holds the countdown until its transcript decides; after 30 s of holds it returns false. A cancel
     * ends the command's job, so the call then never returns.
     */
    suspend fun countdown(millis: Long, text: (secondsLeft: Int) -> String): Boolean
}

/** What happened to instant command number [command] of the kind [kind] (its class's simple name), never its words. */
internal data class CommandReport(val command: Int, val kind: String, val outcome: Outcome)

/**
 * One part of the executor: the text commands (plan 3B), the phone commands (plan 3C) or the screen commands. [run]
 * returns null for a command that the part does not take.
 */
internal interface CommandPart {
    suspend fun run(command: Command, heard: String, conversation: CommandConversation): CommandResult?

    /** Whether a password field has the focus, as [CommandRunner.privateInput] asks; it makes no binder call. */
    fun privateInput(): Boolean = false
}

/**
 * What every part of the executor needs (design spec, section 9.5). [screen] is null while Kestrel's accessibility
 * service is off. [options] are the owner's options for the instant commands, read at each command. [openSettings] opens
 * Kestrel's settings app, on [SettingsPage] when one is given, and asks for the permission when one is given. [report]
 * logs a failure by its classes only.
 */
internal class CommandEnvironment(
    val context: Context,
    val screen: () -> ScreenSession?,
    val options: StateFlow<CommandOptions>,
    val targets: ScreenTargets,
    val scope: CoroutineScope,
    val openSettings: (page: SettingsPage?, permission: String?) -> Unit,
    val report: (String, Throwable) -> Unit,
)
